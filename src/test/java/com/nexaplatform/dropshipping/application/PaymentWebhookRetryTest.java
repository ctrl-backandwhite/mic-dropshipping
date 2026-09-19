package com.nexaplatform.dropshipping.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.exception.WebhookProcessingException;
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
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PaymentJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reintento de los eventos de pasarela.
 *
 * <p>Los tres webhooks (Stripe, PayPal, Coinbase) atrapaban CUALQUIER excepción, dejaban una línea de log
 * y devolvían {@code "ok"}. El controlador lo envuelve en un 200, así que la pasarela daba el evento por
 * entregado y **no lo reintentaba nunca más**: el dinero seguía cobrado en su lado mientras aquí el
 * pedido no pasaba a PAID —o la recarga no se acreditaba—. Un corte de base de datos de un minuto se
 * llevaba por delante todos los cobros de ese minuto, en silencio.
 *
 * <p>La distinción que fijan estos tests: un fallo de PROCESO se propaga (5xx → la pasarela reenvía;
 * Stripe insiste tres días), mientras que un evento que no nos incumbe o que no casa con ningún pago se
 * responde 200, porque reintentarlo no arregla nada y la pasarela acabaría desactivando el endpoint.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentWebhookRetryTest {

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
    OpsAlertService opsAlertService;

    private PaymentUseCaseImpl useCase() {
        return new PaymentUseCaseImpl(List.<PaymentGateway>of(), paymentRepository, paymentJpaRepositoryAdapter,
                userRepository, orderRepository, walletUseCase, org.mockito.Mockito.mock(com.nexaplatform.dropshipping.infrastructure.integration.stripe.StripeService.class), auditLogger, partnerPlanSyncService,
                customerSubscriptionUseCase, subscriptionNotificationService, new ObjectMapper(), orderEmailService,
                currencyRateService, new OrderAmounts(currencyRateService), stockService, mock(SupplierPurchaseService.class), opsAlertService, mock(CartService.class));
    }

    private static final String STRIPE_PAID = """
            {"data":{"object":{"id":"pi_3Abc","metadata":{"paymentId":"%s"}}}}""";


    /**
     * Sujeto bajo prueba, construido una sola vez por test. Se instancia en {@code @BeforeEach} y no
     * en la declaración del campo porque los dobles de prueba se inyectan DESPUÉS de crear la clase:
     * hacerlo antes lo dejaría con todas las dependencias a nulo. Tenerlo aparte permite además que la
     * lambda de cada aserción contenga una sola llamada capaz de lanzar, así que el fallo esperado sólo
     * puede venir del método bajo prueba.
     */
    private PaymentUseCaseImpl subject;

    @BeforeEach
    void buildSubject() {
        subject = useCase();
    }

    // ------------------------------------------------------------ el fallo se propaga

    @Test
    void siFallaElProcesadoDelCobroElEventoNoSeDaPorAtendido() {
        UUID paymentId = UUID.randomUUID();
        // Un corte de base de datos en mitad de la confirmación.
        when(paymentRepository.findById(paymentId)).thenThrow(new IllegalStateException("connection reset"));
        // El cuerpo se compone FUERA de la lambda para que dentro quede una sola llamada capaz de lanzar.
        String event = STRIPE_PAID.formatted(paymentId);

        assertThatThrownBy(() -> subject.handleStripeEvent("payment_intent.succeeded", event))
                .isInstanceOf(WebhookProcessingException.class)
                .hasMessageContaining("reintente");
    }

    @Test
    void unFalloDeProcesadoAvisaAlResponsable() {
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findById(paymentId)).thenThrow(new IllegalStateException("connection reset"));
        String event = STRIPE_PAID.formatted(paymentId);

        assertThatThrownBy(() -> subject.handleStripeEvent("payment_intent.succeeded", event))
                .isInstanceOf(WebhookProcessingException.class);

        // Si el fallo persiste, los reintentos también fallan: alguien tiene que enterarse.
        verify(opsAlertService).paymentFailed(eqIgnoringNull("stripe"), anyString(), anyString(), anyString());
    }

    @Test
    void elFalloDePaypalYDeCoinbaseTambienSePropaga() {
        when(paymentRepository.findByProviderAndProviderRef(anyString(), anyString()))
                .thenThrow(new IllegalStateException("connection reset"));

        assertThatThrownBy(() -> subject.handlePayPalEvent(
                """
                        {"event_type":"PAYMENT.CAPTURE.COMPLETED","resource":{"id":"5X0"}}"""))
                .isInstanceOf(WebhookProcessingException.class);

        assertThatThrownBy(() -> subject.handleCoinbaseEvent(
                """
                        {"event":{"type":"charge:confirmed","data":{"code":"ABC"}}}"""))
                .isInstanceOf(WebhookProcessingException.class);
    }

    // ------------------------------------------------------------ lo que NO se reintenta

    @Test
    void unEventoQueNoCasaConNingunPagoSeDaPorAtendido() {
        // Reintentarlo no lo arreglaría nunca, y la pasarela acabaría desactivando el endpoint.
        when(paymentRepository.findByProviderAndProviderRef(anyString(), anyString())).thenReturn(Optional.empty());

        assertThat(subject.handlePayPalEvent(
                """
                        {"event_type":"PAYMENT.CAPTURE.COMPLETED","resource":{"id":"desconocido"}}"""))
                .isEqualTo("no-match");
        assertThat(subject.handleCoinbaseEvent(
                """
                        {"event":{"type":"charge:confirmed","data":{"code":"desconocido"}}}"""))
                .isEqualTo("no-match");
    }

    @Test
    void unEventoDeStripeSinPagoIdentificableSeDaPorAtendido() {
        when(paymentRepository.findByProviderAndProviderRef(anyString(), anyString())).thenReturn(Optional.empty());

        assertThat(subject.handleStripeEvent("payment_intent.succeeded",
                """
                        {"data":{"object":{"id":"pi_desconocido"}}}"""))
                .isEqualTo("no-match");
    }

    @Test
    void unEventoDeTipoQueNoNosIncumbeSeDaPorAtendidoSinTocarNada() {
        UUID paymentId = UUID.randomUUID();
        Payment p = new Payment();
        p.setId(paymentId);
        p.setStatus(PaymentStatus.PENDING);
        p.setMethod(PaymentMethod.CARD);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));

        assertThat(subject.handleStripeEvent("payment_intent.created", STRIPE_PAID.formatted(paymentId)))
                .isEqualTo("ok");

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void unEventoDeSuscripcionSeSincronizaYNoTocaElCobroDePedidos() {
        String payload = """
                {"data":{"object":{"id":"sub_123","status":"active","current_period_end":1800000000}}}""";

        assertThat(subject.handleStripeEvent("customer.subscription.updated", payload)).isEqualTo("ok");

        verify(customerSubscriptionUseCase).syncFromStripe(anyString(), anyString(), any(), any());
        verify(partnerPlanSyncService).onSubscriptionEvent(anyString(), anyString(), anyString());
    }

    @Test
    void unFalloDeCobroDeLaSuscripcionAvisaAlTitular() {
        String payload = """
                {"data":{"object":{"id":"in_1","subscription":"sub_123"}}}""";

        assertThat(subject.handleStripeEvent("invoice.payment_failed", payload)).isEqualTo("ok");

        verify(subscriptionNotificationService).planPaymentFailed("sub_123");
    }

    private static String eqIgnoringNull(String value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
