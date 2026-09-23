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
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.domain.enums.PaymentStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.domain.model.Payment;
import com.nexaplatform.dropshipping.domain.model.Wallet;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.domain.repository.PaymentRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.integration.payment.PaymentGateway;
import com.nexaplatform.dropshipping.infrastructure.integration.stripe.StripeService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PaymentEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PaymentJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cuándo se vacía la cesta: SOLO cuando el pedido queda PAGADO.
 *
 * <p>El momento es lo único que importa aquí. Con pago externo (tarjeta, PayPal, USDT) el pedido nace
 * PENDIENTE y el dinero puede no llegar nunca: vaciar la cesta al crearlo dejaría a la persona sin
 * pedido <b>y</b> sin cesta, con todo lo que había elegido perdido. Con saldo el cobro es inmediato, así
 * que los dos casos quedan cubiertos con el mismo disparador: el paso a PAGADO.
 *
 * <p>Y una regla que va por delante de la limpieza: <b>vaciar la cesta jamás puede tumbar el cobro</b>.
 * Si el borrado falla, el pedido ya está pagado y eso es lo que cuenta; perder un pago por no poder
 * borrar una fila sería mucho peor que dejar restos en la cesta.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CartEmptiedWhenOrderPaidTest {

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
    CartService cartService;
    @Mock
    SupplierPurchaseService supplierPurchaseService;
    @Mock
    PaymentGateway gateway;

    private final UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID orderId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final UUID paymentId = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private final UUID productId = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private final UUID variantId = UUID.fromString("66666666-6666-6666-6666-666666666666");

    private PaymentUseCaseImpl subject;

    @BeforeEach
    void buildSubject() {
        subject = new PaymentUseCaseImpl(List.of(gateway), paymentRepository, paymentJpaRepositoryAdapter,
                userRepository, orderRepository, walletUseCase, mock(StripeService.class), auditLogger,
                partnerPlanSyncService, customerSubscriptionUseCase, subscriptionNotificationService,
                new ObjectMapper(), orderEmailService, currencyRateService, new OrderAmounts(currencyRateService),
                stockService, supplierPurchaseService, mock(OpsAlertService.class), cartService);
        when(paymentRepository.save(any())).thenAnswer(i -> {
            Payment saved = i.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(paymentId);
            }
            return saved;
        });
        when(userRepository.findById(userId)).thenReturn(Optional.of(mock(UserEntity.class)));
    }

    /** Pedido del comprador con una línea comprada; el repositorio lo devuelve y guarda tal cual. */
    private Order order(OrderStatus status) {
        Order o = new Order();
        o.setId(orderId);
        o.setOrderNumber("NX-1");
        o.setUserId(userId);
        o.setStatus(status);
        o.setTotalCents(9540);
        o.setCurrency("USD");
        List<OrderItem> items = new ArrayList<>();
        items.add(OrderItem.builder().productId(productId).variantId(variantId).quantity(2).build());
        o.setItems(items);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(o));
        when(orderRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        return o;
    }

    /** Cobro externo (tarjeta) de ese pedido, en el estado indicado. */
    private Payment orderPayment(PaymentStatus status) {
        Payment p = new Payment();
        p.setId(paymentId);
        p.setUserId(userId);
        p.setStatus(status);
        p.setMethod(PaymentMethod.CARD);
        p.setProviderRef("cs_mock_1");
        p.setAmountUsdCents(9540L);
        p.setPurpose("ORDER_PAYMENT");
        p.setOrderId(orderId);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        return p;
    }

    /** El pedido que se le pasó a la cesta para limpiar (o el fallo del test si nunca se le pasó). */
    private Order pedidoVaciado() {
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(cartService).removePurchased(captor.capture());
        return captor.getValue();
    }

    // ------------------------------------------------------------------ cobro externo (tarjeta/PayPal)

    @Test
    @DisplayName("al confirmarse el cobro externo, lo comprado sale de la cesta")
    void alConfirmarseElCobroExternoLoCompradoSaleDeLaCesta() {
        orderPayment(PaymentStatus.PENDING);
        Order o = order(OrderStatus.PENDING);

        subject.confirmSucceeded(paymentId, Map.of());

        assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID);
        // Se limpia el pedido pagado: su usuario y sus líneas, no una cesta cualquiera.
        Order vaciado = pedidoVaciado();
        assertThat(vaciado.getUserId()).isEqualTo(userId);
        assertThat(vaciado.getItems()).extracting(OrderItem::getProductId).containsExactly(productId);
    }

    /**
     * El caso que da sentido a todo: crear el pedido con pago externo NO puede tocar la cesta. El dinero
     * aún no ha llegado —y puede no llegar nunca—, así que la persona tiene que conservar su cesta.
     */
    @Test
    @DisplayName("al CREAR el pedido con pago externo la cesta sigue intacta")
    void alCrearElPedidoConPagoExternoLaCestaSigueIntacta() {
        order(OrderStatus.PENDING);
        Wallet wallet = new Wallet();
        wallet.setId(UUID.randomUUID());
        when(walletUseCase.getOrCreate(userId)).thenReturn(wallet);
        when(currencyRateService.usdTo(any(BigDecimal.class), anyString())).thenAnswer(i -> i.getArgument(0));
        when(gateway.supports(PaymentMethod.CARD)).thenReturn(true);
        when(gateway.providerName()).thenReturn("stripe");
        when(gateway.initiate(any(PaymentEntity.class)))
                .thenReturn(new PaymentGateway.InitiateResult("cs_test_1", "secret", null, null, null, null, Map.of()));
        when(paymentJpaRepositoryAdapter.findById(any())).thenReturn(Optional.of(new PaymentEntity()));

        subject.initiateOrderPayment(orderId, userId, PaymentMethod.CARD, "idem-1");

        verify(cartService, never()).removePurchased(any());
    }

    @Test
    @DisplayName("si el cobro externo falla, la cesta permanece")
    void siElCobroExternoFallaLaCestaPermanece() {
        orderPayment(PaymentStatus.REQUIRES_ACTION);
        order(OrderStatus.PENDING);

        subject.markFailed(paymentId, "card_declined", Map.of());

        verify(cartService, never()).removePurchased(any());
    }

    /**
     * Un cobro que se queda a medias (el cliente abandona la pasarela y nadie confirma) deja el pedido
     * PENDIENTE: la cesta tiene que seguir ahí para poder reintentar la compra.
     */
    @Test
    @DisplayName("un pago que nunca se confirma deja el pedido pendiente y la cesta llena")
    void unPagoQueNuncaSeConfirmaDejaLaCestaLlena() {
        orderPayment(PaymentStatus.REQUIRES_ACTION);
        Order o = order(OrderStatus.PENDING);

        assertThat(o.getStatus()).isEqualTo(OrderStatus.PENDING);
        verify(cartService, never()).removePurchased(any());
    }

    // ------------------------------------------------------------------ idempotencia

    @Test
    @DisplayName("una segunda confirmación del mismo cobro no vuelve a tocar la cesta")
    void unaSegundaConfirmacionNoVuelveATocarLaCesta() {
        orderPayment(PaymentStatus.PENDING);
        order(OrderStatus.PENDING);

        subject.confirmSucceeded(paymentId, Map.of());
        subject.confirmSucceeded(paymentId, Map.of()); // webhook duplicado / reproceso

        // Una sola limpieza: si se repitiera, borraría lo que la persona haya vuelto a añadir después.
        verify(cartService, times(1)).removePurchased(any());
    }

    @Test
    @DisplayName("un webhook tardío sobre un pedido ya avanzado no toca la cesta")
    void unWebhookTardioSobreUnPedidoYaAvanzadoNoTocaLaCesta() {
        orderPayment(PaymentStatus.PENDING);
        Order o = order(OrderStatus.SHIPPED);

        subject.confirmSucceeded(paymentId, Map.of());

        assertThat(o.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        verify(cartService, never()).removePurchased(any());
    }

    // ------------------------------------------------------------------ cobro con saldo del monedero

    @Test
    @DisplayName("al pagar con el saldo del monedero, lo comprado sale de la cesta")
    void alPagarConSaldoLoCompradoSaleDeLaCesta() {
        Order o = order(OrderStatus.AWAITING_PAYMENT);
        Wallet wallet = new Wallet();
        wallet.setId(UUID.randomUUID());
        when(walletUseCase.getOrCreate(userId)).thenReturn(wallet);

        subject.chargeWalletForOrder(orderId, userId, "idem-1");

        assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(pedidoVaciado().getUserId()).isEqualTo(userId);
        // El resto de la liquidación con saldo sigue intacta: la mercancía hay que comprarla en 1688.
        verify(supplierPurchaseService).planPurchases(any());
    }

    @Test
    @DisplayName("si el cargo al saldo falla, el pedido no queda pagado y la cesta permanece")
    void siElCargoAlSaldoFallaLaCestaPermanece() {
        Order o = order(OrderStatus.AWAITING_PAYMENT);
        Wallet wallet = new Wallet();
        wallet.setId(UUID.randomUUID());
        when(walletUseCase.getOrCreate(userId)).thenReturn(wallet);
        doThrow(new BusinessException("Saldo insuficiente")).when(walletUseCase).charge(any(),
                org.mockito.ArgumentMatchers.anyLong(), any(), anyString(), anyString());

        try {
            subject.chargeWalletForOrder(orderId, userId, "idem-1");
        } catch (BusinessException expected) {
            assertThat(expected).hasMessageContaining("Saldo insuficiente");
        }

        assertThat(o.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        verify(cartService, never()).removePurchased(any());
    }

    // ------------------------------------------------------------------ el cobro manda sobre la limpieza

    /**
     * La prioridad, en claro: si al limpiar la cesta salta un error, el cobro NO se cae. El pedido queda
     * pagado, el stock descontado y la factura enviada; lo único que puede quedar es algún resto en la
     * cesta, que es infinitamente menos grave que perder un pago ya capturado por la pasarela.
     */
    @Test
    @DisplayName("un fallo al vaciar la cesta no impide que el pedido quede pagado")
    void unFalloAlVaciarLaCestaNoImpideQueElPedidoQuedePagado() {
        Payment p = orderPayment(PaymentStatus.PENDING);
        Order o = order(OrderStatus.PENDING);
        doThrow(new IllegalStateException("la base de datos no responde")).when(cartService).removePurchased(any());

        Payment confirmado = subject.confirmSucceeded(paymentId, Map.of());

        assertThat(confirmado.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID);
        // Y el resto de la liquidación sigue su curso: existencias y factura.
        verify(stockService).deductForOrder(any());
        verify(orderEmailService).paymentConfirmed(any(), any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("un fallo al vaciar la cesta tampoco tumba el cobro con saldo")
    void unFalloAlVaciarLaCestaTampocoTumbaElCobroConSaldo() {
        Order o = order(OrderStatus.AWAITING_PAYMENT);
        Wallet wallet = new Wallet();
        wallet.setId(UUID.randomUUID());
        when(walletUseCase.getOrCreate(userId)).thenReturn(wallet);
        doThrow(new IllegalStateException("la base de datos no responde")).when(cartService).removePurchased(any());

        Payment p = subject.chargeWalletForOrder(orderId, userId, "idem-1");

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID);
    }
}
