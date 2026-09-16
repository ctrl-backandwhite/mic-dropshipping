package com.nexaplatform.dropshipping.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.application.service.OrderAmounts;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import org.springframework.test.util.ReflectionTestUtils;
import com.nexaplatform.dropshipping.application.service.AuditLogger;
import com.nexaplatform.dropshipping.application.service.OpsAlertService;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.application.service.PartnerPlanSyncService;
import com.nexaplatform.dropshipping.application.service.StockService;
import com.nexaplatform.dropshipping.application.service.SupplierPurchaseService;
import com.nexaplatform.dropshipping.application.service.SubscriptionNotificationService;
import com.nexaplatform.dropshipping.application.usecase.CustomerSubscriptionUseCase;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.application.service.CartService;
import com.nexaplatform.dropshipping.application.usecase.impl.PaymentUseCaseImpl;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.domain.enums.PaymentStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.Payment;
import com.nexaplatform.dropshipping.domain.model.Wallet;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.domain.repository.PaymentRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.integration.payment.PaymentGateway;
import com.nexaplatform.dropshipping.infrastructure.integration.payment.PayPalGateway;
import com.nexaplatform.dropshipping.infrastructure.integration.payment.StripeGateway;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PaymentJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Confirmación y devolución de un cobro CONTRA LA PASARELA (no por la vía simulada).
 *
 * <p>La regla que atraviesa todo el fichero: un pago solo pasa a cobrado —y una devolución solo se da por
 * hecha— cuando la pasarela lo dice. Si el estado que devuelve Stripe/PayPal no es de éxito, el pedido no
 * se marca pagado y el pago no se marca devuelto; lo contrario significa mercancía enviada sin cobrar o
 * dinero devuelto que nunca salió.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov09PaymentGatewayFlowsTest {

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
    @Mock
    StripeGateway stripe;
    @Mock
    PayPalGateway paypal;

    private final UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID orderId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final UUID paymentId = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private PaymentUseCaseImpl subject;

    @BeforeEach
    void buildSubject() {
        when(stripe.supports(PaymentMethod.CARD)).thenReturn(true);
        when(paypal.supports(PaymentMethod.PAYPAL)).thenReturn(true);
        subject = useCase(List.of(stripe, paypal));
        when(paymentRepository.save(any())).thenAnswer(i -> {
            Payment saved = i.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
    }

    private PaymentUseCaseImpl useCase(List<PaymentGateway> gateways) {
        return new PaymentUseCaseImpl(gateways, paymentRepository, paymentJpaRepositoryAdapter, userRepository,
                orderRepository, walletUseCase, org.mockito.Mockito.mock(com.nexaplatform.dropshipping.infrastructure.integration.stripe.StripeService.class), auditLogger, partnerPlanSyncService, customerSubscriptionUseCase,
                subscriptionNotificationService, new ObjectMapper(), orderEmailService, currencyRateService, new OrderAmounts(currencyRateService),
                stockService, mock(SupplierPurchaseService.class), opsAlertService, mock(CartService.class));
    }

    private Payment payment(PaymentMethod method, PaymentStatus status, String providerRef, boolean forOrder) {
        Payment p = new Payment();
        p.setId(paymentId);
        p.setUserId(userId);
        p.setMethod(method);
        p.setStatus(status);
        p.setProviderRef(providerRef);
        p.setAmountUsdCents(2500L);
        p.setPurpose(forOrder ? "ORDER_PAYMENT" : "RECHARGE");
        if (forOrder) {
            p.setOrderId(orderId);
        }
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        return p;
    }

    private Order order(OrderStatus status) {
        Order o = new Order();
        o.setId(orderId);
        o.setOrderNumber("NX-1");
        o.setUserId(userId);
        o.setStatus(status);
        o.setTotalCents(2500);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(o));
        when(orderRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        return o;
    }

    // ---------------------------------------------------------------- captura de PayPal

    @Test
    void soloSeCapturaEnPaypalUnPagoQueSeAbrioEnPaypal() {
        payment(PaymentMethod.CARD, PaymentStatus.REQUIRES_ACTION, "cs_test_1", false);

        assertThatThrownBy(() -> subject.capturePayPal(userId, paymentId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Not a PayPal payment");
    }

    @Test
    void sinPasarelaDePaypalConfiguradaNoSeCaptura() {
        // Hay un bean que dice soportar PAYPAL pero no es la pasarela real: no se puede capturar a ciegas.
        payment(PaymentMethod.PAYPAL, PaymentStatus.REQUIRES_ACTION, "PAYID-1", false);
        PaymentGateway impostor = mock(PaymentGateway.class);
        when(impostor.supports(PaymentMethod.PAYPAL)).thenReturn(true);
        PaymentUseCaseImpl sinPaypal = useCase(List.of(impostor));

        assertThatThrownBy(() -> sinPaypal.capturePayPal(userId, paymentId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PayPal gateway not configured");
    }

    @Test
    void unaCapturaCompletadaEnPaypalAbonaElSaldoDeLaRecarga() {
        Payment p = payment(PaymentMethod.PAYPAL, PaymentStatus.REQUIRES_ACTION, "PAYID-1", false);
        when(paypal.capture("PAYID-1")).thenReturn(Map.of("status", "COMPLETED"));

        Payment out = subject.capturePayPal(userId, paymentId);

        assertThat(out.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        verify(walletUseCase).deposit(eq(userId), eq(2500L), eq(p.getId()), anyString(), anyString());
    }

    @Test
    void unaCapturaRechazadaEnPaypalDejaElPagoFallidoYNoAbonaNada() {
        payment(PaymentMethod.PAYPAL, PaymentStatus.REQUIRES_ACTION, "PAYID-1", false);
        when(paypal.capture("PAYID-1")).thenReturn(Map.of("status", "DECLINED"));

        Payment out = subject.capturePayPal(userId, paymentId);

        assertThat(out.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(out.getErrorMessage()).contains("DECLINED");
        verify(walletUseCase, never()).deposit(any(), anyLong(), any(), anyString(), anyString());
    }

    // ---------------------------------------------------------------- confirmar el cobro de un pedido

    @Test
    void confirmarUnPedidoConTarjetaSoloLoDejaPagadoSiStripeDiceQueEstaPagado() {
        payment(PaymentMethod.CARD, PaymentStatus.REQUIRES_ACTION, "cs_test_real", true);
        Order o = order(OrderStatus.AWAITING_PAYMENT);
        when(stripe.retrieveCheckoutSession("cs_test_real")).thenReturn(Map.of("status", "paid"));

        Payment out = subject.confirmOrderPayment(userId, orderId, paymentId);

        assertThat(out.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void siLaSesionDeStripeNoEstaPagadaElPedidoNoSeDaPorCobrado() {
        // Marcar PAID aquí significaría enviar la mercancía de un carrito que el comprador abandonó.
        payment(PaymentMethod.CARD, PaymentStatus.REQUIRES_ACTION, "cs_test_real", true);
        Order o = order(OrderStatus.AWAITING_PAYMENT);
        when(stripe.retrieveCheckoutSession("cs_test_real")).thenReturn(Map.of("status", "open"));

        Payment out = subject.confirmOrderPayment(userId, orderId, paymentId);

        assertThat(out.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        verify(stockService, never()).deductForOrder(any());
    }

    @Test
    void confirmarUnPedidoDePaypalDelegaEnLaCapturaDelLadoServidor() {
        payment(PaymentMethod.PAYPAL, PaymentStatus.REQUIRES_ACTION, "PAYID-1", true);
        order(OrderStatus.AWAITING_PAYMENT);
        when(paypal.capture("PAYID-1")).thenReturn(Map.of("status", "COMPLETED"));

        assertThat(subject.confirmOrderPayment(userId, orderId, paymentId).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        verify(paypal).capture("PAYID-1");
    }

    @Test
    void confirmarUnPedidoYaCobradoNoVuelveATocarLaPasarela() {
        payment(PaymentMethod.CARD, PaymentStatus.SUCCEEDED, "cs_test_real", true);

        assertThat(subject.confirmOrderPayment(userId, orderId, paymentId).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        verify(stripe, never()).retrieveCheckoutSession(anyString());
    }

    @Test
    void unMetodoSinConfirmacionSoportadaNoDaPorCobradoElPedido() {
        payment(PaymentMethod.USDT, PaymentStatus.REQUIRES_ACTION, "usdt-tx", true);

        assertThatThrownBy(() -> subject.confirmOrderPayment(userId, orderId, paymentId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Confirm not supported");
    }

    // ---------------------------------------------------------------- confirmar una recarga real

    @Test
    void laRecargaConTarjetaSoloAbonaSaldoSiStripeConfirmaElCobro() {
        Payment p = payment(PaymentMethod.CARD, PaymentStatus.REQUIRES_ACTION, "cs_test_real", false);
        when(stripe.retrieveCheckoutSession("cs_test_real")).thenReturn(Map.of("status", "paid"));

        subject.confirmRecharge(userId, paymentId);

        verify(walletUseCase).deposit(eq(userId), eq(2500L), eq(p.getId()), anyString(), anyString());
    }

    @Test
    void unaRecargaQueStripeNoDaPorPagadaNoAbonaSaldoYFalla() {
        // Es la puerta por la que se colaría "saldo gratis": abrir la recarga y confirmarla sin pagar.
        payment(PaymentMethod.CARD, PaymentStatus.REQUIRES_ACTION, "cs_test_real", false);
        when(stripe.retrieveCheckoutSession("cs_test_real")).thenReturn(Map.of("status", "open"));

        assertThatThrownBy(() -> subject.confirmRecharge(userId, paymentId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Payment not completed");

        verify(walletUseCase, never()).deposit(any(), anyLong(), any(), anyString(), anyString());
    }

    @Test
    void unaRecargaEnCriptoNoSeConfirmaAMano() {
        payment(PaymentMethod.USDT, PaymentStatus.REQUIRES_ACTION, "usdt-tx", false);

        assertThatThrownBy(() -> subject.confirmRecharge(userId, paymentId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unsupported method for recharge confirm");
    }

    // ---------------------------------------------------------------- devoluciones

    @Test
    void elReembolsoDePaypalVaContraLaCapturaNoContraLaOrden() {
        // Reembolsar la ORDEN en vez de la CAPTURA falla en PayPal: el dinero se devuelve sobre la captura.
        Payment p = payment(PaymentMethod.PAYPAL, PaymentStatus.SUCCEEDED, "PAYID-1", true);
        p.setProviderResponse(Map.of("purchase_units",
                List.of(Map.of("payments", Map.of("captures", List.of(Map.of("id", "CAP-9")))))));
        when(paypal.refund("CAP-9", 500L)).thenReturn(Map.of("status", "COMPLETED"));

        Payment out = subject.refundOrderPayment(orderId, paymentId, 500L);

        assertThat(out.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(paypal).refund("CAP-9", 500L);
    }

    @Test
    void sinIdDeCapturaSeReembolsaContraLaReferenciaDelProveedor() {
        Payment p = payment(PaymentMethod.PAYPAL, PaymentStatus.SUCCEEDED, "PAYID-1", true);
        p.setProviderResponse(Map.of());
        when(paypal.refund("PAYID-1", 0L)).thenReturn(Map.of("mock", true));

        subject.refundOrderPayment(orderId, paymentId, 0L);

        verify(paypal).refund("PAYID-1", 0L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DECLINED", "FAILED", ""})
    void siPaypalNoAceptaLaDevolucionElPagoNoSeMarcaComoDevuelto(String status) {
        Payment p = payment(PaymentMethod.PAYPAL, PaymentStatus.SUCCEEDED, "PAYID-1", true);
        p.setProviderResponse(Map.of());
        when(paypal.refund(anyString(), anyLong())).thenReturn(Map.of("status", status));

        assertThatThrownBy(() -> subject.refundOrderPayment(orderId, paymentId, 500L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PayPal refund failed");

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void elReembolsoDeStripeVaContraElPaymentIntentGuardado() {
        Payment p = payment(PaymentMethod.CARD, PaymentStatus.SUCCEEDED, "cs_test_real", true);
        p.setProviderResponse(Map.of("paymentIntent", "pi_777"));
        when(stripe.refund("pi_777", 500L)).thenReturn(Map.of("status", "succeeded"));

        Payment out = subject.refundOrderPayment(orderId, paymentId, 500L);

        assertThat(out.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        // Queda constancia de la devolución y de cuándo se hizo, para poder cuadrarla después.
        assertThat(out.getProviderResponse()).containsKey("refund").containsKey("refunded_at");
        verify(stripe).refund("pi_777", 500L);
    }

    @Test
    void sinPaymentIntentGuardadoStripeReembolsaContraLaReferenciaDelProveedor() {
        Payment p = payment(PaymentMethod.CARD, PaymentStatus.SUCCEEDED, "pi_3Abc", true);
        p.setProviderResponse(Map.of());
        when(stripe.refund("pi_3Abc", 500L)).thenReturn(Map.of("status", "pending"));

        assertThat(subject.refundOrderPayment(orderId, paymentId, 500L).getStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void siStripeNoAceptaLaDevolucionElPagoNoSeMarcaComoDevuelto() {
        Payment p = payment(PaymentMethod.CARD, PaymentStatus.SUCCEEDED, "pi_3Abc", true);
        p.setProviderResponse(Map.of());
        when(stripe.refund(anyString(), anyLong())).thenReturn(Map.of("status", "failed"));

        assertThatThrownBy(() -> subject.refundOrderPayment(orderId, paymentId, 500L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Stripe refund failed");

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    // ---------------------------------------------------------------- consultas y atajos de entrada

    @Test
    void unPagoInexistenteNoSeInventa() {
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> subject.find(paymentId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void elListadoDePagosDeUnUsuarioVieneOrdenadoDelMasReciente() {
        Payment p = payment(PaymentMethod.CARD, PaymentStatus.SUCCEEDED, "cs_test_real", false);
        when(paymentRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(p));

        assertThat(subject.listForUser(userId)).containsExactly(p);
    }

    @Test
    void elListadoDePagosDeUnPedidoSePideAlRepositorioPorPedido() {
        Payment p = payment(PaymentMethod.CARD, PaymentStatus.SUCCEEDED, "cs_test_real", true);
        when(paymentRepository.findByOrderIdOrderByCreatedAtDesc(orderId)).thenReturn(List.of(p));

        assertThat(subject.listOrderPayments(orderId)).containsExactly(p);
    }

    @Test
    void pagarConSaldoNoAbreNingunCobroEnLaPasarela() {
        // El atajo con wallet=true tiene que ir al monedero: si cayera en la pasarela se cobraría dos veces.
        Order o = order(OrderStatus.AWAITING_PAYMENT);
        when(userRepository.findById(userId)).thenReturn(Optional.of(mock(UserEntity.class)));
        Wallet w = new Wallet();
        w.setId(UUID.randomUUID());
        when(walletUseCase.getOrCreate(userId)).thenReturn(w);

        Payment p = subject.initiateMeOrderPayment(userId, orderId, true, PaymentMethod.CARD, "k1");

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(p.getProvider()).isEqualTo("wallet");
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(walletUseCase).charge(eq(userId), eq(2500L), eq(orderId), eq("order-charge-" + orderId), anyString());
        verify(stripe, never()).initiate(any());
    }

    @Test
    void elCobroDeUnPartnerSeAtribuyeAUnUsuarioDerivadoDeSuIdentidadOauth() {
        // El id del pagador se deriva del subject del token: dos llamadas del mismo partner deben caer
        // siempre en la misma cuenta, no crear una distinta cada vez.
        UUID esperado = UUID.nameUUIDFromBytes("partner:acme-client".getBytes());
        Order o = order(OrderStatus.AWAITING_PAYMENT);
        o.setUserId(null);
        o.setPartnerAppId(esperado); // el pedido pertenece a ESTE partner (assertOrderOwnedByPartner)
        when(userRepository.findById(esperado)).thenReturn(Optional.of(mock(UserEntity.class)));
        Wallet w = new Wallet();
        w.setId(UUID.randomUUID());
        when(walletUseCase.getOrCreate(esperado)).thenReturn(w);
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("acme-client").build();

        Payment p = subject.initiatePartnerOrderPayment(jwt, orderId, true, PaymentMethod.CARD, "k1");

        assertThat(p.getUserId()).isEqualTo(esperado);
    }

    // ---------------------------------------------------------------- seguridad: IDOR y mock en prod

    @Test
    void confirmarElPagoDeUnPedidoDeOtroUsuarioDevuelve404() {
        // IDOR: el pago pertenece a `userId`; otro usuario autenticado no puede confirmarlo.
        payment(PaymentMethod.CARD, PaymentStatus.REQUIRES_ACTION, "cs_test_real", true);

        assertThatThrownBy(() -> subject.confirmOrderPayment(UUID.randomUUID(), orderId, paymentId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void capturarElPagoDeOtroUsuarioDevuelve404() {
        payment(PaymentMethod.PAYPAL, PaymentStatus.REQUIRES_ACTION, "PAYID-1", false);

        assertThatThrownBy(() -> subject.capturePayPal(UUID.randomUUID(), paymentId))
                .isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------- pasarela apagada en un entorno real (fail-closed)
    //
    // Estas cuatro prueban el MISMO descuido por cuatro caminos. `STRIPE_ENABLED` y `STRIPE_SECRET_KEY`
    // son variables distintas y el validador de arranque no mira las pasarelas: producción puede levantar
    // con la pasarela "activa" y sin credencial. A partir de ahí la pasarela deja de llamar a nadie y
    // FABRICA la respuesta —{status: succeeded|paid|COMPLETED, mock: true}—, que además viene con estado
    // de éxito, así que la acepta la primera condición sin llegar a mirar la marca `mock`.
    //
    // Lo que se rompía en producción: un pedido pasaba a PAGADO sin cobro y se despachaba la mercancía; y
    // una cancelación marcaba el pago DEVUELTO, cancelaba el pedido y le mandaba al cliente el correo de
    // "reembolso procesado" sin que se hubiera movido un euro — dejando además el reintento correcto
    // bloqueado, porque devolver exige que el pago siga en SUCCEEDED.
    //
    // El guarda que ya existe (assertMockAllowed) solo cubre el pago INICIADO con la pasarela apagada, que
    // se reconoce por el prefijo del providerRef. Aquí la referencia es REAL: el pago se inició cuando la
    // pasarela funcionaba y se confirma o se devuelve después de que se quedara sin credencial.

    @Test
    void enPerfilProUnaDevolucionQueStripeNoHaHechoNoMarcaElPagoComoDevuelto() {
        ReflectionTestUtils.setField(subject, "activeProfiles", "pro");
        Payment p = payment(PaymentMethod.CARD, PaymentStatus.SUCCEEDED, "pi_777", true);
        p.setProviderResponse(Map.of());
        when(stripe.refund("pi_777", 500L)).thenReturn(Map.of("status", "succeeded", "mock", true));

        assertThatThrownBy(() -> subject.refundOrderPayment(orderId, paymentId, 500L))
                .isInstanceOf(BusinessException.class);

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void enPerfilProUnaDevolucionQuePaypalNoHaHechoNoMarcaElPagoComoDevuelto() {
        ReflectionTestUtils.setField(subject, "activeProfiles", "pre");
        Payment p = payment(PaymentMethod.PAYPAL, PaymentStatus.SUCCEEDED, "PAYID-1", true);
        p.setProviderResponse(Map.of());
        when(paypal.refund(anyString(), anyLong())).thenReturn(Map.of("status", "COMPLETED", "mock", true));

        assertThatThrownBy(() -> subject.refundOrderPayment(orderId, paymentId, 500L))
                .isInstanceOf(BusinessException.class);

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void enPerfilProUnPedidoNoSeDaPorCobradoConUnaRespuestaFabricadaPorStripe() {
        ReflectionTestUtils.setField(subject, "activeProfiles", "pro");
        payment(PaymentMethod.CARD, PaymentStatus.REQUIRES_ACTION, "cs_test_real", true);
        Order o = order(OrderStatus.AWAITING_PAYMENT);
        when(stripe.retrieveCheckoutSession("cs_test_real")).thenReturn(Map.of("status", "paid", "mock", true));

        assertThatThrownBy(() -> subject.confirmOrderPayment(userId, orderId, paymentId))
                .isInstanceOf(BusinessException.class);

        assertThat(o.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        verify(stockService, never()).deductForOrder(any());
    }

    @Test
    void enPerfilProUnaCapturaFabricadaPorPaypalNoDaElPedidoPorCobrado() {
        ReflectionTestUtils.setField(subject, "activeProfiles", "pro");
        Payment p = payment(PaymentMethod.PAYPAL, PaymentStatus.REQUIRES_ACTION, "PAYID-1", true);
        Order o = order(OrderStatus.AWAITING_PAYMENT);
        when(paypal.capture("PAYID-1")).thenReturn(Map.of("status", "COMPLETED", "mock", true));

        assertThatThrownBy(() -> subject.capturePayPal(userId, paymentId))
                .isInstanceOf(BusinessException.class);

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REQUIRES_ACTION);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
    }

    /**
     * La contrapartida: fuera de pro/pre la vía simulada tiene que seguir funcionando, que es para lo que
     * existe. Si esta prueba se pone roja, el arreglo ha dejado el entorno de desarrollo sin poder cobrar.
     */
    @Test
    void fueraDeProLaRespuestaSimuladaSigueSiendoValida() {
        payment(PaymentMethod.CARD, PaymentStatus.REQUIRES_ACTION, "cs_test_real", true);
        Order o = order(OrderStatus.AWAITING_PAYMENT);
        when(stripe.retrieveCheckoutSession("cs_test_real")).thenReturn(Map.of("status", "paid", "mock", true));

        assertThat(subject.confirmOrderPayment(userId, orderId, paymentId).getStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void enPerfilProLaConfirmacionMockEstaProhibida() {
        // Fail-closed: en pro/pre no se acepta una confirmación simulada (dinero/pedido gratis).
        ReflectionTestUtils.setField(subject, "activeProfiles", "pro,kibana");
        payment(PaymentMethod.CARD, PaymentStatus.REQUIRES_ACTION, "cs_mock_x", false);

        assertThatThrownBy(() -> subject.confirmMockRecharge(userId, paymentId))
                .isInstanceOf(BusinessException.class);
    }
}
