package com.nexaplatform.dropshipping.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.application.service.AuditLogger;
import com.nexaplatform.dropshipping.application.service.CartService;
import com.nexaplatform.dropshipping.application.service.OpsAlertService;
import com.nexaplatform.dropshipping.application.service.OrderAmounts;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.application.service.PartnerPlanSyncService;
import com.nexaplatform.dropshipping.application.service.StockService;
import com.nexaplatform.dropshipping.application.service.SubscriptionNotificationService;
import com.nexaplatform.dropshipping.application.service.SupplierPurchaseService;
import com.nexaplatform.dropshipping.application.usecase.CustomerSubscriptionUseCase;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.application.usecase.impl.PaymentUseCaseImpl;
import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.domain.enums.PaymentStatus;
import com.nexaplatform.dropshipping.domain.model.Payment;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.domain.repository.PaymentRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.integration.payment.PaymentGateway;
import com.nexaplatform.dropshipping.infrastructure.integration.payment.StripeGateway;
import com.nexaplatform.dropshipping.infrastructure.integration.stripe.StripeService;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PaymentJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reembolso con tarjeta: de qué identificador tira Stripe para devolver el dinero.
 *
 * <p>El cobro va por <b>Checkout hospedado</b>, y Stripe NO crea el PaymentIntent al crear la sesión:
 * {@code payment_intent} viene a null y así se guardaba en {@code providerResponse}. El identificador
 * real solo se escribía al confirmar desde la vuelta del navegador; cuando ganaba la carrera el webhook
 * {@code payment_intent.succeeded} —que llega en el mismo segundo del cobro— esa confirmación se saltaba
 * por idempotencia y el {@code pi_…} no se guardaba nunca.
 *
 * <p>Al reembolsar, {@code getOrDefault("paymentIntent", providerRef)} devolvía ese null (la clave existe,
 * así que el respaldo no entra) y se le mandaba a Stripe la cadena {@code "null"}; con la clave ausente se
 * mandaba el {@code cs_…} de la sesión, que tampoco es reembolsable. En los dos casos Stripe respondía
 * {@code resource_missing} y el cliente veía un 422 genérico: el pedido no se podía cancelar.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentStripeRefundTest {

    private static final String SESSION_ID = "cs_test_a1ywJrEtie9kLrFCNXc87lZ";
    private static final String REAL_INTENT = "pi_3U6E7MFbialTsRVB1rpFVZj4";

    @Mock
    PaymentRepository paymentRepository;
    @Mock
    PaymentJpaRepositoryAdapter paymentJpaRepositoryAdapter;
    @Mock
    UserRepository userRepository;
    @Mock
    OrderRepository orderRepository;
    @Mock
    WalletUseCase walletUseCase;
    @Mock
    AuditLogger auditLogger;
    @Mock
    PartnerPlanSyncService partnerPlanSyncService;
    @Mock
    CustomerSubscriptionUseCase customerSubscriptionUseCase;
    @Mock
    SubscriptionNotificationService subscriptionNotificationService;
    @Mock
    OrderEmailService orderEmailService;
    @Mock
    CurrencyRateService currencyRateService;
    @Mock
    StockService stockService;
    @Mock
    StripeGateway stripeGateway;

    private final UUID orderId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();

    private PaymentUseCaseImpl subject;

    @BeforeEach
    void buildSubject() {
        when(stripeGateway.supports(PaymentMethod.CARD)).thenReturn(true);
        when(paymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        subject = new PaymentUseCaseImpl(List.<PaymentGateway>of(stripeGateway), paymentRepository,
                paymentJpaRepositoryAdapter, userRepository, orderRepository, walletUseCase,
                mock(StripeService.class), auditLogger, partnerPlanSyncService, customerSubscriptionUseCase,
                subscriptionNotificationService, new ObjectMapper(), orderEmailService, currencyRateService,
                new OrderAmounts(currencyRateService), stockService, mock(SupplierPurchaseService.class),
                mock(OpsAlertService.class), mock(CartService.class));
    }

    /** Pago de pedido ya cobrado, con la respuesta del proveedor tal cual quedó guardada. */
    private Payment cardPayment(Map<String, Object> providerResponse) {
        Payment p = Payment.builder().method(PaymentMethod.CARD).status(PaymentStatus.SUCCEEDED)
                .amountUsdCents(3209).provider("stripe").providerRef(SESSION_ID).orderId(orderId)
                .userId(UUID.randomUUID()).userEmail("comprador@nx036.test").providerResponse(providerResponse)
                .build();
        p.setId(paymentId);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        return p;
    }

    // --------------------------------------------------- se resuelve el identificador que Stripe acepta

    @Test
    void conElPaymentIntentGuardadoANuloSeRecuperaDeLaSesionDeCheckout() {
        Map<String, Object> guardado = new HashMap<>();
        guardado.put("id", SESSION_ID);
        guardado.put("paymentIntent", null); // lo que devuelve Stripe al CREAR la sesión
        Payment p = cardPayment(guardado);
        when(stripeGateway.retrieveCheckoutSession(SESSION_ID))
                .thenReturn(Map.of("status", "paid", "paymentIntent", REAL_INTENT));
        when(stripeGateway.refund(REAL_INTENT, 0)).thenReturn(Map.of("status", "succeeded", "id", "re_1"));

        Payment result = subject.refundOrderPayment(orderId, paymentId, 0);

        verify(stripeGateway).refund(REAL_INTENT, 0);
        assertThat(result.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        // Queda guardado para no volver a preguntárselo a Stripe en el siguiente reembolso.
        assertThat(p.getProviderResponse()).containsEntry("paymentIntent", REAL_INTENT);
    }

    @Test
    void sinLaClavePaymentIntentTampocoSeLeMandaLaSesionComoSiFueraUnCobro() {
        // Si el JSON guardado perdió la clave, el respaldo era el providerRef: un cs_… que Stripe rechaza.
        Payment p = cardPayment(new HashMap<>(Map.of("id", SESSION_ID)));
        when(stripeGateway.retrieveCheckoutSession(SESSION_ID))
                .thenReturn(Map.of("status", "paid", "paymentIntent", REAL_INTENT));
        when(stripeGateway.refund(REAL_INTENT, 0)).thenReturn(Map.of("status", "succeeded"));

        subject.refundOrderPayment(orderId, paymentId, 0);

        verify(stripeGateway).refund(REAL_INTENT, 0);
        verify(stripeGateway, never()).refund(SESSION_ID, 0);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void conElPaymentIntentYaGuardadoNoSeConsultaLaSesion() {
        cardPayment(new HashMap<>(Map.of("paymentIntent", REAL_INTENT)));
        when(stripeGateway.refund(REAL_INTENT, 0)).thenReturn(Map.of("status", "succeeded"));

        subject.refundOrderPayment(orderId, paymentId, 0);

        verify(stripeGateway).refund(REAL_INTENT, 0);
        verify(stripeGateway, never()).retrieveCheckoutSession(anyString());
    }

    @Test
    void elCobroConTarjetaGuardadaSeReembolsaPorSuPropioPaymentIntent() {
        // Vía off-session/3DS: el identificador viaja bajo otra clave y el providerRef ya es un pi_…
        Payment p = Payment.builder().method(PaymentMethod.CARD).status(PaymentStatus.SUCCEEDED)
                .amountUsdCents(3209).provider("stripe").providerRef(REAL_INTENT).orderId(orderId)
                .userId(UUID.randomUUID()).userEmail("comprador@nx036.test")
                .providerResponse(new HashMap<>(Map.of("stripe_payment_intent", REAL_INTENT, "off_session", true)))
                .build();
        p.setId(paymentId);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        when(stripeGateway.refund(REAL_INTENT, 0)).thenReturn(Map.of("status", "succeeded"));

        subject.refundOrderPayment(orderId, paymentId, 0);

        verify(stripeGateway).refund(REAL_INTENT, 0);
        verify(stripeGateway, never()).retrieveCheckoutSession(anyString());
    }

    // --------------------------------------------------- el motivo real de Stripe no se pierde

    @Test
    void siStripeRechazaElReembolsoElMotivoLlegaEnElError() {
        cardPayment(new HashMap<>(Map.of("paymentIntent", REAL_INTENT)));
        when(stripeGateway.refund(anyString(), anyLong()))
                .thenReturn(Map.of("status", "failed", "error", "No such payment_intent: 'null'"));

        assertThatThrownBy(() -> subject.refundOrderPayment(orderId, paymentId, 0))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No such payment_intent");
    }

    @Test
    void siNoHayNingunIdentificadorUtilizableNoSeInventaUnaLlamadaAStripe() {
        // Ni pi_ guardado ni sesión recuperable: se aborta ANTES de llamar, con un motivo legible.
        Payment p = cardPayment(new HashMap<>(Map.of("id", SESSION_ID)));
        when(stripeGateway.retrieveCheckoutSession(SESSION_ID))
                .thenReturn(Map.of("status", "error", "error", "No such checkout.session"));

        assertThatThrownBy(() -> subject.refundOrderPayment(orderId, paymentId, 0))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PaymentIntent");
        verify(stripeGateway, never()).refund(anyString(), anyLong());
        // El pago NO puede quedar marcado como devuelto si Stripe no ha devuelto nada.
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    // --------------------------------------------------- el webhook deja de abrir el agujero

    @Test
    void elWebhookGuardaElIdentificadorDelCobroParaPoderReembolsarDespues() {
        Payment p = Payment.builder().method(PaymentMethod.CARD).status(PaymentStatus.REQUIRES_ACTION)
                .amountUsdCents(2500).provider("stripe").providerRef(SESSION_ID).userId(UUID.randomUUID())
                .userEmail("comprador@nx036.test").providerResponse(nullSeededResponse()).build();
        p.setId(paymentId);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        String evento = """
                {"data":{"object":{"id":"%s","metadata":{"paymentId":"%s"}}}}"""
                .formatted(REAL_INTENT, paymentId);

        String body = subject.handleStripeEvent("payment_intent.succeeded", evento);

        assertThat(body).isEqualTo("ok");
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(p.getProviderResponse()).containsEntry("paymentIntent", REAL_INTENT);
    }

    private static Map<String, Object> nullSeededResponse() {
        Map<String, Object> m = new HashMap<>();
        m.put("id", SESSION_ID);
        m.put("paymentIntent", null);
        return m;
    }
}
