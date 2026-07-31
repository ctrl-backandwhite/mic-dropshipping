package com.nexaplatform.dropshipping.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.application.service.OrderAmounts;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.service.AuditLogger;
import com.nexaplatform.dropshipping.application.service.OpsAlertService;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.application.service.PartnerPlanSyncService;
import com.nexaplatform.dropshipping.application.service.StockService;
import com.nexaplatform.dropshipping.application.service.SubscriptionNotificationService;
import com.nexaplatform.dropshipping.application.usecase.CustomerSubscriptionUseCase;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Invariantes del cobro: lo que NUNCA debe pasar con el dinero.
 *
 * <p>{@code PaymentUseCaseImpl} es la clase que cobra —tarjeta, PayPal, cripto y saldo— y estaba al 8,7%
 * de cobertura. Los cinco tests que había miraban mapeos y opciones de recarga; ninguno tocaba la
 * confirmación, el reembolso ni el cargo al monedero. Aquí se fijan las reglas que, si se rompen,
 * cuestan dinero real: doble abono, saldo gratis, reembolso de lo no cobrado y confirmación de pagos
 * ajenos.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentSettlementInvariantsTest {

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

    private final UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID otherUserId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID orderId = UUID.fromString("33333333-3333-3333-3333-333333333333");

    /**
     * Identificador de pago fijo. Se declara aquí, y no dentro de cada helper, para que las lambdas de
     * las aserciones contengan UNA sola llamada capaz de lanzar: con {@code p.getId()} dentro, un fallo
     * al leer el identificador daría el test por bueno por la razón equivocada.
     */
    private final UUID paymentId = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private PaymentUseCaseImpl useCase() {
        return useCase(List.of());
    }

    private PaymentUseCaseImpl useCase(List<PaymentGateway> gateways) {
        return new PaymentUseCaseImpl(gateways, paymentRepository, paymentJpaRepositoryAdapter, userRepository,
                orderRepository, walletUseCase, auditLogger, partnerPlanSyncService, customerSubscriptionUseCase,
                subscriptionNotificationService, new ObjectMapper(), orderEmailService, currencyRateService, new OrderAmounts(currencyRateService),
                stockService, mock(OpsAlertService.class));
    }

    private Payment recharge(PaymentStatus status, String providerRef) {
        Payment p = new Payment();
        p.setId(paymentId);
        p.setUserId(userId);
        p.setStatus(status);
        p.setMethod(PaymentMethod.CARD);
        p.setProviderRef(providerRef);
        p.setAmountUsdCents(2500L);
        p.setPurpose("RECHARGE");
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        // JPA asigna el id al persistir; el mock hace lo mismo porque la auditoría lo mete en un Map.of,
        // que no admite valores nulos.
        when(paymentRepository.save(any())).thenAnswer(i -> {
            Payment saved = i.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
        return p;
    }

    private Payment orderPayment(PaymentStatus status, String providerRef) {
        Payment p = recharge(status, providerRef);
        p.setPurpose("ORDER_PAYMENT");
        p.setOrderId(orderId);
        return p;
    }

    private Order order(OrderStatus status) {
        Order o = new Order();
        o.setId(orderId);
        o.setOrderNumber("NX-1");
        o.setUserId(userId);
        o.setStatus(status);
        o.setTotalCents(9540);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(o));
        when(orderRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        return o;
    }


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

    // ---------------------------------------------------------------- abono del saldo

    @Test
    void confirmarDosVecesLaMismaRecargaAbonaElSaldoUnaSolaVez() {
        Payment p = recharge(PaymentStatus.PENDING, "cs_mock_1");

        useCase().confirmSucceeded(p.getId(), Map.of());
        useCase().confirmSucceeded(p.getId(), Map.of());   // reintento del webhook, doble clic, reenvío

        verify(walletUseCase).deposit(eq(userId), eq(2500L), eq(p.getId()), anyString(), anyString());
    }

    @Test
    void elAbonoDeLaRecargaLlevaClaveDeIdempotenciaDerivadaDelPago() {
        // La clave es lo que impide que dos webhooks del mismo pago abonen dos veces aunque lleguen a la
        // vez por hilos distintos; sin ella la protección de arriba se cae en cuanto hay concurrencia.
        Payment p = recharge(PaymentStatus.PENDING, "cs_mock_1");

        useCase().confirmSucceeded(p.getId(), Map.of());

        verify(walletUseCase).deposit(eq(userId), eq(2500L), eq(p.getId()), eq("deposit-" + p.getId()), anyString());
    }

    @Test
    void confirmarElPagoDeUnPedidoNoAbonaSaldoAlComprador() {
        // Cobrar un pedido y acreditar saldo son ramas excluyentes: si se cruzaran, el comprador pagaría
        // el pedido y encima se llevaría el importe en el monedero.
        Payment p = orderPayment(PaymentStatus.PENDING, "cs_mock_1");
        order(OrderStatus.AWAITING_PAYMENT);

        useCase().confirmSucceeded(p.getId(), Map.of());

        verify(walletUseCase, never()).deposit(any(), anyLong(), any(), anyString(), anyString());
    }

    // ---------------------------------------------------------------- estado del pedido y stock

    @Test
    void confirmarElPagoDejaElPedidoPagadoYDescuentaExistenciasUnaVez() {
        Payment p = orderPayment(PaymentStatus.PENDING, "cs_mock_1");
        Order o = order(OrderStatus.AWAITING_PAYMENT);

        useCase().confirmSucceeded(p.getId(), Map.of());
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID);

        useCase().confirmSucceeded(p.getId(), Map.of());   // segunda confirmación
        verify(stockService).deductForOrder(any());        // pero una sola deducción
    }

    @Test
    void unPedidoYaEnviadoNoRetrocedeAPagadoNiVuelveADescontarExistencias() {
        // Un webhook que llega tarde (o se reprocesa) no puede tirar hacia atrás un pedido que ya avanzó.
        Payment p = orderPayment(PaymentStatus.PENDING, "cs_mock_1");
        Order o = order(OrderStatus.SHIPPED);

        useCase().confirmSucceeded(p.getId(), Map.of());

        assertThat(o.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        verify(stockService, never()).deductForOrder(any());
    }

    // ---------------------------------------------------------------- saldo gratis

    @ParameterizedTest
    @ValueSource(strings = {"cs_test_51Abc", "cs_live_51Abc", "pi_3Abc", "PAYID-REAL", ""})
    void laViaMockNoAcreditaSaldoDeUnaPasarelaReal(String realRef) {
        // Sin esta comprobación cualquiera inicia una recarga y la "confirma" sin pagar: dinero libre.
        recharge(PaymentStatus.PENDING, realRef);

        assertThatThrownBy(() -> subject.confirmMockRecharge(userId, paymentId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("pasarela de pago real");

        verify(walletUseCase, never()).deposit(any(), anyLong(), any(), anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"cs_mock_1", "paypal_mock_1", "pi_mock_1"})
    void laViaMockSiAcreditaLosPagosSinteticosDeModoSimulado(String mockRef) {
        Payment p = recharge(PaymentStatus.PENDING, mockRef);

        useCase().confirmMockRecharge(userId, p.getId());

        verify(walletUseCase).deposit(eq(userId), eq(2500L), eq(p.getId()), anyString(), anyString());
    }

    @Test
    void nadieConfirmaLaRecargaDeOtroYElPagoAjenoNiSeReconoce() {
        // 404 y no 403: un 403 confirmaría al atacante que ese identificador de pago existe.
        recharge(PaymentStatus.PENDING, "cs_mock_1");

        assertThatThrownBy(() -> subject.confirmMockRecharge(otherUserId, paymentId))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> subject.confirmRecharge(otherUserId, paymentId))
                .isInstanceOf(NotFoundException.class);

        verify(walletUseCase, never()).deposit(any(), anyLong(), any(), anyString(), anyString());
    }

    @Test
    void confirmarUnaRecargaYaAbonadaNoVuelveAAbonar() {
        Payment p = recharge(PaymentStatus.SUCCEEDED, "cs_mock_1");

        assertThat(useCase().confirmRecharge(userId, p.getId()).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        verify(walletUseCase, never()).deposit(any(), anyLong(), any(), anyString(), anyString());
    }

    // ---------------------------------------------------------------- reembolsos

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "FAILED", "REFUNDED"})
    void soloSeReembolsaLoQueDeVerdadSeCobro(String status) {
        // Reembolsar un pago no cobrado saca dinero de la plataforma por algo que nunca entró.
        orderPayment(PaymentStatus.valueOf(status), "pi_3Abc");

        assertThatThrownBy(() -> subject.refundOrderPayment(orderId, paymentId, 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("succeeded");
    }

    @Test
    void unMetodoSinReembolsoSoportadoNoMarcaElPagoComoDevuelto() {
        Payment p = orderPayment(PaymentStatus.SUCCEEDED, "usdt-tx");
        p.setMethod(PaymentMethod.USDT);

        assertThatThrownBy(() -> subject.refundOrderPayment(orderId, paymentId, 100L))
                .isInstanceOf(BusinessException.class);

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void unPagoDeOtroPedidoNoSeConfirmaNiSeReembolsa() {
        UUID foreignOrder = UUID.randomUUID();
        orderPayment(PaymentStatus.SUCCEEDED, "pi_3Abc");

        assertThatThrownBy(() -> subject.refundOrderPayment(foreignOrder, paymentId, 100L))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> subject.confirmOrderPayment(foreignOrder, paymentId))
                .isInstanceOf(NotFoundException.class);
    }

    // ---------------------------------------------------------------- cargo al monedero

    @Test
    void cobrarConSaldoDescuentaElTotalDelPedidoYLoDejaPagado() {
        Order o = order(OrderStatus.AWAITING_PAYMENT);
        Wallet wallet = new Wallet();
        wallet.setId(UUID.randomUUID());
        when(walletUseCase.getOrCreate(userId)).thenReturn(wallet);
        when(userRepository.findById(userId)).thenReturn(Optional.of(
                mock(com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity.class)));
        when(paymentRepository.save(any())).thenAnswer(i -> {
            Payment saved = i.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });

        Payment p = useCase().chargeWalletForOrder(orderId, userId, "idem-1");

        // El importe cargado es el TOTAL del pedido, no el subtotal: si se cobrara el subtotal, el envío
        // y el impuesto se regalarían en cada compra pagada con saldo.
        verify(walletUseCase).charge(eq(userId), eq(9540L), eq(orderId), eq("idem-1"), anyString());
        assertThat(p.getAmountUsdCents()).isEqualTo(9540L);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void siElCargoAlSaldoFallaElPedidoNoQuedaPagado() {
        // El orden importa: primero se cobra, y sólo si el cobro sale bien se marca PAID. Al revés, un
        // saldo insuficiente dejaría el pedido cobrado sin haber cobrado.
        Order o = order(OrderStatus.AWAITING_PAYMENT);
        Wallet wallet = new Wallet();
        wallet.setId(UUID.randomUUID());
        when(walletUseCase.getOrCreate(userId)).thenReturn(wallet);
        when(userRepository.findById(userId)).thenReturn(Optional.of(
                mock(com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity.class)));
        doThrow(new BusinessException("Saldo insuficiente"))
                .when(walletUseCase).charge(any(), anyLong(), any(), anyString(), anyString());

        assertThatThrownBy(() -> subject.chargeWalletForOrder(orderId, userId, "idem-1"))
                .isInstanceOf(BusinessException.class);

        assertThat(o.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void noSeCobraConSaldoUnPedidoDeUnUsuarioQueNoExiste() {
        order(OrderStatus.AWAITING_PAYMENT);
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> subject.chargeWalletForOrder(orderId, userId, "idem-1"))
                .isInstanceOf(NotFoundException.class);

        verify(walletUseCase, never()).charge(any(), anyLong(), any(), anyString(), anyString());
    }

    // ---------------------------------------------------------------- fallos

    @Test
    void marcarFallidoConservaLaRespuestaPreviaDelProveedor() {
        // El diagnóstico de una incidencia de cobro depende de no perder lo que dijo la pasarela antes.
        Payment p = recharge(PaymentStatus.PENDING, "pi_3Abc");
        p.setProviderResponse(new java.util.HashMap<>(Map.of("intent", "pi_3Abc")));

        Payment failed = useCase().markFailed(p.getId(), "card_declined", Map.of("code", "insufficient_funds"));

        assertThat(failed.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(failed.getErrorMessage()).isEqualTo("card_declined");
        assertThat(failed.getProviderResponse()).containsEntry("intent", "pi_3Abc")
                .containsEntry("code", "insufficient_funds");
    }
}
