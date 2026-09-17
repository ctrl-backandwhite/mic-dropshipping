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
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.domain.model.Payment;
import com.nexaplatform.dropshipping.domain.model.Wallet;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.domain.repository.PaymentRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.integration.payment.PaymentGateway;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PaymentEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PaymentJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
 * Arranque del cobro de un pedido: qué se le pide a la pasarela y qué se rechaza antes de llegar a ella.
 *
 * <p>El importe que se manda a cobrar se calcula SUMANDO línea a línea en la moneda de liquidación, no
 * convirtiendo el total de una vez, para que lo cobrado coincida al céntimo con lo que el comprador vio
 * en el carrito. Ya hubo un incidente por publicar el importe en USD junto a una divisa que decía EUR
 * (9,54 € anunciados como 10,87); el cobro siempre fue correcto, el dato publicado no.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentInitiationTest {

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
    PaymentGateway gateway;

    private final UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID orderId = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @AfterEach
    void clearCurrency() {
        CurrencyHolder.clear();
    }

    private PaymentUseCaseImpl useCase() {
        return new PaymentUseCaseImpl(List.of(gateway), paymentRepository, paymentJpaRepositoryAdapter, userRepository,
                orderRepository, walletUseCase, org.mockito.Mockito.mock(com.nexaplatform.dropshipping.infrastructure.integration.stripe.StripeService.class), auditLogger, partnerPlanSyncService, customerSubscriptionUseCase,
                subscriptionNotificationService, new ObjectMapper(), orderEmailService, currencyRateService, new OrderAmounts(currencyRateService),
                stockService, mock(SupplierPurchaseService.class), opsAlertService, mock(CartService.class));
    }

    /** Pedido de 2 × 40,00 $ + 10,00 $ de envío + 5,40 $ de impuesto = 95,40 $. */
    private Order order(OrderStatus status, int totalCents) {
        Order o = new Order();
        o.setId(orderId);
        o.setOrderNumber("NX-1");
        o.setUserId(userId);
        o.setStatus(status);
        o.setTotalCents(totalCents);
        o.setShippingCents(1000);
        o.setTaxCents(540);
        OrderItem item = new OrderItem();
        item.setUnitPriceCents(4000);
        item.setQuantity(2);
        o.setItems(List.of(item));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(o));
        return o;
    }

    private void happyGateway() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(mock(UserEntity.class)));
        Wallet w = new Wallet();
        w.setId(UUID.randomUUID());
        when(walletUseCase.getOrCreate(userId)).thenReturn(w);
        when(currencyRateService.usdTo(any(BigDecimal.class), anyString()))
                .thenAnswer(i -> i.getArgument(0));
        when(currencyRateService.decimalsOf(anyString())).thenReturn(2);            // 1:1 para que las cuentas se lean solas
        when(paymentRepository.save(any())).thenAnswer(i -> {
            Payment p = i.getArgument(0);
            if (p.getId() == null) {
                p.setId(UUID.randomUUID());
            }
            return p;
        });
        when(paymentJpaRepositoryAdapter.findById(any())).thenReturn(Optional.of(mock(PaymentEntity.class)));
        when(gateway.supports(any())).thenReturn(true);
        when(gateway.providerName()).thenReturn("stripe");
        when(gateway.initiate(any())).thenReturn(new PaymentGateway.InitiateResult(
                "cs_test_1", null, "https://pay/1", null, null, null, Map.of()));
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

    // ------------------------------------------------------------ lo que se rechaza antes de cobrar

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "REFUNDED"})
    void noSeCobraUnPedidoCanceladoNiYaDevuelto(String status) {
        order(OrderStatus.valueOf(status), 9540);

        assertThatThrownBy(() -> subject.initiateOrderPayment(orderId, userId, PaymentMethod.CARD, "k1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be paid");

        verify(gateway, never()).initiate(any());
    }

    @Test
    void noSeCobraPorDebajoDelMinimoDeLaPasarela() {
        // Por debajo de 1,00 $ Stripe cobra más de comisión que el importe; el intento sólo genera ruido.
        order(OrderStatus.AWAITING_PAYMENT, 99);

        assertThatThrownBy(() -> subject.initiateOrderPayment(orderId, userId, PaymentMethod.CARD, "k1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("below $1.00");
    }

    @Test
    void sinMetodoDePagoNoSeLlegaAConsultarElPedido() {
        assertThatThrownBy(() -> subject.initiateOrderPayment(orderId, userId, null, "k1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("paymentMethod required");
    }

    @Test
    void noSeCobraUnPedidoDeUnUsuarioQueNoExiste() {
        order(OrderStatus.AWAITING_PAYMENT, 9540);
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> subject.initiateOrderPayment(orderId, userId, PaymentMethod.CARD, "k1"))
                .isInstanceOf(NotFoundException.class);

        verify(gateway, never()).initiate(any());
    }

    @Test
    void unPedidoInexistenteNoAbreUnCobro() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> subject.initiateOrderPayment(orderId, userId, PaymentMethod.CARD, "k1"))
                .isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------ idempotencia

    @Test
    void reintentarConLaMismaClaveDevuelveElCobroYaAbiertoSinLlamarALaPasarela() {
        // Sin esto, un doble clic en "Pagar" abre dos sesiones de cobro para el mismo pedido.
        Payment ya = new Payment();
        ya.setId(UUID.randomUUID());
        // De quién es el cobro importa: la reutilización por clave es solo para quien lo abrió.
        ya.setUserId(userId);
        ya.setStatus(PaymentStatus.REQUIRES_ACTION);
        when(paymentRepository.findByIdempotencyKey("k1")).thenReturn(Optional.of(ya));

        assertThat(useCase().initiateOrderPayment(orderId, userId, PaymentMethod.CARD, "k1")).isSameAs(ya);

        verify(gateway, never()).initiate(any());
        verify(paymentRepository, never()).save(any());
    }

    // ------------------------------------------------------------ importe y divisa

    @Test
    void elImporteACobrarSeSumaLineaALineaMasEnvioEImpuesto() {
        CurrencyHolder.set("USD");
        order(OrderStatus.AWAITING_PAYMENT, 9540);
        happyGateway();

        Payment p = useCase().initiateOrderPayment(orderId, userId, PaymentMethod.CARD, "k1");

        // 40,00 × 2 + 10,00 + 5,40 = 95,40 — la misma suma que ve el comprador en el carrito.
        assertThat(p.getSettlementAmount()).isEqualByComparingTo("95.40");
        assertThat(p.getAmountUsdCents()).isEqualTo(9540L);
    }

    @Test
    void conTarjetaYNavegandoEnEurosSeLiquidaEnEurosYNoEnDolares() {
        CurrencyHolder.set("EUR");
        order(OrderStatus.AWAITING_PAYMENT, 9540);
        happyGateway();

        Payment p = useCase().initiateOrderPayment(orderId, userId, PaymentMethod.CARD, "k1");

        assertThat(p.getSettlementCurrency()).isEqualTo("EUR");
        // El importe publicado va emparejado con su divisa: anunciar dólares diciendo "EUR" fue un
        // incidente real, aunque lo cobrado siempre fuese correcto.
        assertThat(p.getCurrencyDisplay()).isEqualTo("EUR");
    }

    @Test
    void conCriptoSeLiquidaEnUsdtIndependientementeDeLaDivisaQueSeNavegue() {
        CurrencyHolder.set("EUR");
        order(OrderStatus.AWAITING_PAYMENT, 9540);
        happyGateway();

        Payment p = useCase().initiateOrderPayment(orderId, userId, PaymentMethod.USDT, "k1");

        assertThat(p.getSettlementCurrency()).isEqualTo("USDT");
        assertThat(p.getSettlementAmount()).isEqualByComparingTo("95.40");
    }

    @Test
    void conOtraDivisaDistintaDeEuroSeLiquidaEnDolares() {
        CurrencyHolder.set("GBP");
        order(OrderStatus.AWAITING_PAYMENT, 9540);
        happyGateway();

        assertThat(useCase().initiateOrderPayment(orderId, userId, PaymentMethod.CARD, "k1")
                .getSettlementCurrency()).isEqualTo("USD");
    }

    // ------------------------------------------------------------ fallo de la pasarela

    @Test
    void siLaPasarelaNoAbreElCobroSeAvisaAlResponsableYNoSeSilenciaElError() {
        // Un fallo aquí no lo arregla el cliente: ese método de pago no está cobrando y se pierden ventas.
        CurrencyHolder.set("USD");
        order(OrderStatus.AWAITING_PAYMENT, 9540);
        happyGateway();
        when(gateway.initiate(any())).thenThrow(new IllegalStateException("stripe unreachable"));

        assertThatThrownBy(() -> subject.initiateOrderPayment(orderId, userId, PaymentMethod.CARD, "k1"))
                .isInstanceOf(IllegalStateException.class);

        verify(opsAlertService).paymentFailed(anyString(), anyString(), anyString(), anyString());
    }
}
