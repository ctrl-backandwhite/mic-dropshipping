package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.PartnerDtos.AddressInput;
import com.nexaplatform.dropshipping.api.dto.in.MeCheckoutDtoIn;
import com.nexaplatform.dropshipping.application.notifications.NotificationsPublisher;
import com.nexaplatform.dropshipping.application.service.AffiliateProgramService;
import com.nexaplatform.dropshipping.application.service.CartService;
import com.nexaplatform.dropshipping.application.service.CheckoutTotalsService;
import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService;
import com.nexaplatform.dropshipping.application.service.FulfillmentRouter;
import com.nexaplatform.dropshipping.application.service.UnserviceableZoneService;
import com.nexaplatform.dropshipping.application.service.OperatorCommissionService;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.PromotionService;
import com.nexaplatform.dropshipping.application.service.StockService;
import com.nexaplatform.dropshipping.application.service.SupplierPurchaseService;
import com.nexaplatform.dropshipping.application.service.WebhookDispatcherService;
import com.nexaplatform.dropshipping.application.usecase.PaymentUseCase;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.application.usecase.impl.OrderUseCaseImpl;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import com.nexaplatform.dropshipping.infrastructure.integration.search.OrderIndexer;
import com.nexaplatform.dropshipping.infrastructure.integration.search.OrderSearchService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderTrackingEventRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ShopConnectionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserAddressRepository;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El checkout del cliente y la cesta: quién la vacía y cuándo.
 *
 * <p>Con <b>saldo</b> el cobro ocurre dentro del propio checkout, así que el pedido sale ya PAGADO y lo
 * comprado desaparece de la cesta en el acto. Con <b>tarjeta, PayPal o USDT</b> el checkout solo deja el
 * pedido PENDIENTE —el dinero se cobra después, fuera—, y ahí la cesta no se toca: si se vaciara y el
 * pago se abandonara, la persona se quedaría sin pedido y sin cesta. Esa limpieza la hace el cobro al
 * confirmarse.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CheckoutCartEmptiedTest {

    @Mock
    com.nexaplatform.dropshipping.domain.repository.OrderRepository orderRepository;
    @Mock
    com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderRepository orderEntityRepository;
    @Mock
    ProductRepository productRepository;
    @Mock
    ProductVariantRepository variantRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    ShopConnectionRepository shopConnectionRepository;
    @Mock
    UserAddressRepository userAddressRepository;
    @Mock
    WebhookDispatcherService webhooks;
    @Mock
    WalletUseCase walletUseCase;
    @Mock
    NotificationsPublisher notificationsPublisher;
    @Mock
    PricingService pricingService;
    @Mock
    AffiliateProgramService affiliateProgramService;
    @Mock
    StockService stockService;
    @Mock
    PaymentUseCase paymentUseCase;
    @Mock
    OrderEmailService orderEmailService;
    @Mock
    FulfillmentProvider fulfillment;
    @Mock
    FulfillmentRouter router;
    @Mock
    CheckoutTotalsService checkoutTotalsService;
    @Mock
    OperatorCommissionService operatorCommissionService;
    @Mock
    PromotionService promotionService;
    @Mock
    SupplierPurchaseService supplierPurchaseService;
    @Mock
    OrderTrackingEventRepository trackingRepository;
    @Mock
    OrderIndexer orderIndexer;
    @Mock
    OrderSearchService orderSearchService;
    @Mock
    CartService cartService;

    private final UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID productId = UUID.fromString("55555555-5555-5555-5555-555555555555");

    /** Último pedido guardado: el checkout lo relee por id justo después de crearlo. */
    private final AtomicReference<Order> guardado = new AtomicReference<>();

    private OrderUseCaseImpl subject;

    @BeforeEach
    void setUp() {
        subject = new OrderUseCaseImpl(orderRepository, orderEntityRepository, productRepository, variantRepository,
                userRepository, shopConnectionRepository, userAddressRepository, webhooks, walletUseCase,
                notificationsPublisher, pricingService, affiliateProgramService, stockService, paymentUseCase,
                orderEmailService, fulfillment, router, checkoutTotalsService, subvenciones(), new CustomsDutyLinesService(null), mock(UnserviceableZoneService.class),
                operatorCommissionService, promotionService, supplierPurchaseService,
                mock(com.nexaplatform.dropshipping.application.service.CustomsDeclarationGroupService.class),
                trackingRepository, orderIndexer,
                orderSearchService, cartService);

        ProductEntity p = new ProductEntity();
        p.setId(productId);
        p.setSlug("camisa-roja");
        p.setBasePrice(new BigDecimal("10.00"));
        p.setImages(new ArrayList<>());
        p.setStatus(ProductStatus.ACTIVE);
        when(productRepository.findById(productId)).thenReturn(Optional.of(p));
        when(pricingService.priceFor(any(), any())).thenReturn(new PricingService.PricedAmount(
                new BigDecimal("10.00"), new BigDecimal("20.00"), null, "USD", "$", null, null, null, null, null,
                null, null, null, null));

        when(fulfillment.isSupported("ES")).thenReturn(true);
        when(router.cotizar(anyString(), any(), anyList()))
                .thenReturn(new ShippingQuote(true, "ES", 0, "YunExpress", "Standard", 7, 15, "EU"));
        when(affiliateProgramService.referralDiscountCents(any(), anyLong())).thenReturn(0L);
        CheckoutTotalsService.CheckoutTotals totals = mock(CheckoutTotalsService.CheckoutTotals.class);
        when(totals.blocked()).thenReturn(false);
        when(totals.shippingCents()).thenReturn(0);
        when(totals.taxCents()).thenReturn(0);
        when(totals.totalCents(anyInt())).thenAnswer(i -> i.getArgument(0));
        when(checkoutTotalsService.compute(any(), any(), anyInt(), anyInt(), anyList(), any())).thenReturn(totals);

        // El pedido guardado se recuerda para que el findById posterior del checkout devuelva ESE pedido.
        when(orderRepository.save(any())).thenAnswer(i -> {
            Order o = i.getArgument(0);
            if (o.getId() == null) {
                o.setId(UUID.randomUUID());
            }
            guardado.set(o);
            return o;
        });
        when(orderRepository.findById(any())).thenAnswer(i -> Optional.ofNullable(guardado.get()));
    }

    private static MeCheckoutDtoIn peticion(String metodoPago, UUID productId) {
        MeCheckoutDtoIn req = new MeCheckoutDtoIn();
        req.setShippingAddressInline(new AddressInput("Nombre Apellido", "+34600000000", "cliente@example.com",
                "Calle 1", null, "Madrid", "Madrid", "28001", "ES"));
        req.setItems(List.of(new MeCheckoutDtoIn.Item(productId, null, 2)));
        req.setPaymentMethod(metodoPago);
        return req;
    }

    /** El pedido que se le pasó a la cesta para limpiar (o el fallo del test si nunca se le pasó). */
    private Order pedidoVaciado() {
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(cartService).removePurchased(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("pagando con saldo el pedido sale PAGADO y lo comprado sale de la cesta")
    void pagandoConSaldoLoCompradoSaleDeLaCesta() {
        Order pedido = subject.checkout(userId, peticion("WALLET", productId), "idem-1");

        assertThat(pedido.getStatus()).isEqualTo(OrderStatus.PAID);
        Order vaciado = pedidoVaciado();
        assertThat(vaciado.getUserId()).isEqualTo(userId);
        assertThat(vaciado.getItems()).extracting(OrderItem::getProductId).containsExactly(productId);
    }

    /**
     * Con pago externo el pedido nace PENDIENTE: el dinero se cobra fuera y puede no llegar nunca. La
     * cesta se queda tal cual hasta que el cobro se confirme.
     */
    @Test
    @DisplayName("con pago externo el pedido queda PENDIENTE y la cesta no se toca")
    void conPagoExternoLaCestaNoSeToca() {
        Order pedido = subject.checkout(userId, peticion("CARD", productId), "idem-2");

        assertThat(pedido.getStatus()).isEqualTo(OrderStatus.PENDING);
        verify(cartService, never()).removePurchased(any());
    }

    @Test
    @DisplayName("si el saldo no llega, no hay pedido pagado ni cesta vaciada")
    void siElSaldoNoLlegaNoSeVaciaLaCesta() {
        doThrow(new BusinessException("Saldo insuficiente"))
                .when(walletUseCase).charge(any(), anyLong(), any(), anyString(), anyString());
        MeCheckoutDtoIn req = peticion("WALLET", productId);

        assertThatThrownBy(() -> subject.checkout(userId, req, "idem-3"))
                .isInstanceOf(BusinessException.class);

        verify(cartService, never()).removePurchased(any());
    }

    /**
     * El cobro manda: el saldo ya se ha debitado y el pedido está pagado. Un error limpiando la cesta se
     * registra y se sigue — dejar restos es un mal mucho menor que tumbar una compra ya cobrada.
     */
    @Test
    @DisplayName("un fallo al vaciar la cesta no tumba el checkout pagado con saldo")
    void unFalloAlVaciarLaCestaNoTumbaElCheckout() {
        doThrow(new IllegalStateException("la base de datos no responde"))
                .when(cartService).removePurchased(any());

        Order pedido = subject.checkout(userId, peticion("WALLET", productId), "idem-4");

        assertThat(pedido.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(walletUseCase).charge(any(), anyLong(), any(), anyString(), anyString());
    }

    /** La bolsa de subvención del envío, real y con su suelo puesto (mide importes, no puede ser un cero). */
    private static com.nexaplatform.dropshipping.application.service.ProductSubsidyService subvenciones() {
        com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService divisa =
                org.mockito.Mockito.mock(com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService.class);
        org.mockito.Mockito.lenient().when(divisa.toUsd(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(new java.math.BigDecimal("5.85"));
        return new com.nexaplatform.dropshipping.application.service.ProductSubsidyService(divisa);
    }
}
