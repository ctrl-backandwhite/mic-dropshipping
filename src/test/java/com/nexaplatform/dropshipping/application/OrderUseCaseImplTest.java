package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.AddressInput;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.CreateOrderRequest;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.OrderItemInput;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.notifications.NotificationsPublisher;
import com.nexaplatform.dropshipping.application.service.AffiliateProgramService;
import com.nexaplatform.dropshipping.application.service.CheckoutTotalsService;
import com.nexaplatform.dropshipping.application.service.CustomsValuationService;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.WebhookDispatcherService;
import com.nexaplatform.dropshipping.application.usecase.PaymentUseCase;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.application.service.FulfillmentRouter;
import com.nexaplatform.dropshipping.application.usecase.impl.OrderUseCaseImpl;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.enums.OverThresholdPolicy;
import com.nexaplatform.dropshipping.domain.enums.TaxMode;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ShopConnectionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserAddressRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService.DutyParcel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderUseCaseImplTest {

    @Mock
    OrderRepository orderRepository;
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
    com.nexaplatform.dropshipping.application.service.StockService stockService;
    @Mock
    PaymentUseCase paymentUseCase;
    @Mock
    OrderEmailService orderEmailService;
    @Mock
    FulfillmentProvider cainiao;
    @Mock
    FulfillmentRouter router;
    @Mock
    CheckoutTotalsService checkoutTotalsService;

    @Mock
    com.nexaplatform.dropshipping.application.service.OperatorCommissionService operatorCommissionService;
    @Mock
    com.nexaplatform.dropshipping.application.service.SupplierPurchaseService supplierPurchaseService;
    @Mock
    com.nexaplatform.dropshipping.infrastructure.integration.search.OrderIndexer orderIndexer;
    @Mock
    com.nexaplatform.dropshipping.infrastructure.integration.search.OrderSearchService orderSearchService;

    @org.mockito.Spy
    com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService customsDutyLinesService =
            new com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService(null);

    @InjectMocks
    OrderUseCaseImpl orderUseCase;

    @BeforeEach
    void setup() {
        // Por defecto, sin envío en los tests de billing (no altera el total = subtotal).
        lenient().when(router.cotizar(any(), any(FulfillmentProvider.ParcelSpec.class), anyList()))
                .thenReturn(ShippingQuote.unsupported("XX"));
        // Por defecto, sin impuesto ni recargo de despacho: total = subtotal + envío, como en los
        // tests de billing existentes. El envío devuelto es el mismo que entra (sin handling fee).
        lenient().when(checkoutTotalsService.compute(any(), any(), anyInt(), anyInt(), anyList()))
                .thenAnswer(inv -> noCustomsTotals(inv.getArgument(3)));
    }

    /**
     * Desglose neutro para los tests de billing: sin impuesto, sin recargo de despacho y sin umbral
     * superado, de modo que el envío cobrado es exactamente la tarifa cotizada.
     */
    private static CheckoutTotalsService.CheckoutTotals noCustomsTotals(int shippingBaseCents) {
        CustomsValuationService.CustomsValuation customs = new CustomsValuationService.CustomsValuation("XX",
                TaxMode.DDP, 0, false, OverThresholdPolicy.SURCHARGE, 0, false, "", false);
        return new CheckoutTotalsService.CheckoutTotals(shippingBaseCents, 0, shippingBaseCents, 0, 0, customs);
    }

    /** DROP-637: the checkout now bills the priced amount (retailUsd) from PricingService. */
    private static PricingService.PricedAmount priced(String retail) {
        BigDecimal r = retail == null ? null : new BigDecimal(retail);
        return new PricingService.PricedAmount(r, r, r, "USD", "$", null, null, BigDecimal.ZERO,
                r, BigDecimal.ZERO, BigDecimal.ZERO, null, null, null);
    }

    @Test
    void create_order_with_two_items_computes_totals() {
        UUID productId = UUID.randomUUID();
        ProductEntity product = ProductEntity.builder().status(ProductStatus.ACTIVE).basePrice(new BigDecimal("12.50")).moq(1).titleZh("Widget")
                .build();
        product.setId(productId);
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(pricingService.priceFor(any(), any())).thenReturn(priced("12.50"));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        var req = new CreateOrderRequest("EXT-001",
                new AddressInput("John Doe", "555", "j@x.com", "Line 1", null, "Madrid", "M", "28001", "ES"), null,
                List.of(new OrderItemInput(productId, null, 3)), null);

        Order order = orderUseCase.createOrder(UUID.randomUUID(), null, req);

        assertThat(order.getItems()).hasSize(1);
        assertThat(order.getSubtotalCents()).isEqualTo(3750); // 12.50 * 3
        assertThat(order.getTotalCents()).isEqualTo(3750);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getOrderNumber()).startsWith("NX-");
    }

    /**
     * Destino cuya política aduanera prohíbe vender por encima de su umbral de importación: el pedido se
     * rechaza ANTES de cobrar, en vez de aceptar uno que costaría aranceles y despacho formal no
     * repercutidos al cliente.
     */
    @Test
    void create_order_rejected_when_destination_blocks_over_threshold() {
        UUID productId = UUID.randomUUID();
        ProductEntity product = ProductEntity.builder().status(ProductStatus.ACTIVE).basePrice(new BigDecimal("12.50")).moq(1).titleZh("Widget")
                .build();
        product.setId(productId);
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(pricingService.priceFor(any(), any())).thenReturn(priced("12.50"));
        CustomsValuationService.CustomsValuation blocked = new CustomsValuationService.CustomsValuation("MX",
                TaxMode.DDP, 0, true, OverThresholdPolicy.BLOCK, 0, true, "150 EUR", false);
        when(checkoutTotalsService.compute(any(), any(), anyInt(), anyInt(), anyList()))
                .thenReturn(new CheckoutTotalsService.CheckoutTotals(0, 0, 0, 0, 0, blocked));

        var req = new CreateOrderRequest("EXT-002",
                new AddressInput("John Doe", "555", "j@x.com", "Line 1", null, "CDMX", null, "01000", "MX"), null,
                List.of(new OrderItemInput(productId, null, 3)), null);
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> orderUseCase.createOrder(userId, null, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("MX");
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void create_order_fails_if_product_has_no_price() {
        UUID productId = UUID.randomUUID();
        ProductEntity product = ProductEntity.builder().status(ProductStatus.ACTIVE).titleZh("noprice").moq(1).build();
        product.setId(productId);
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(pricingService.priceFor(any(), any())).thenReturn(priced(null));

        var req = new CreateOrderRequest("EXT", new AddressInput("X", null, null, "L1", null, "C", null, "00000", "ES"),
                null, List.of(new OrderItemInput(productId, null, 1)), null);

        UUID partnerAppId = UUID.randomUUID();
        assertThatThrownBy(() -> orderUseCase.createOrder(partnerAppId, null, req))
                .isInstanceOf(BusinessException.class).hasMessageContaining("no price");
    }

    @Test
    void create_order_fails_if_product_missing() {
        UUID productId = UUID.randomUUID();
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        var req = new CreateOrderRequest("EXT", new AddressInput("X", null, null, "L1", null, "C", null, "00000", "ES"),
                null, List.of(new OrderItemInput(productId, null, 1)), null);

        UUID partnerAppId = UUID.randomUUID();
        assertThatThrownBy(() -> orderUseCase.createOrder(partnerAppId, null, req))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void variant_price_overrides_product_price() {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        ProductEntity product = ProductEntity.builder().status(ProductStatus.ACTIVE).basePrice(new BigDecimal("10")).moq(1).titleZh("p").build();
        product.setId(productId);
        ProductVariantEntity variant = ProductVariantEntity.builder().price(new BigDecimal("15")).sku("V1").stock(10)
                .active(true).build();
        variant.setId(variantId);

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(variantRepository.findById(variantId)).thenReturn(Optional.of(variant));
        when(pricingService.priceFor(any(), any())).thenReturn(priced("15"));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        var req = new CreateOrderRequest("EXT", new AddressInput("X", null, null, "L1", null, "C", null, "00000", "ES"),
                null, List.of(new OrderItemInput(productId, variantId, 2)), null);

        Order order = orderUseCase.createOrder(UUID.randomUUID(), null, req);
        assertThat(order.getSubtotalCents()).isEqualTo(3000); // priced retailUsd 15 * 2 * 100
    }

    @Test
    void createOrder_whenVariantMissing_throwsCartItemUnavailableWithVariantId() {
        // Carrito obsoleto: la variante fue re-importada con otro ID y ya no existe. El checkout debe
        // devolver el código específico CART_ITEM_UNAVAILABLE + el variantId en detail (no un 404 genérico),
        // para que el front identifique y quite la línea rota.
        UUID productId = UUID.randomUUID();
        UUID staleVariantId = UUID.randomUUID();
        ProductEntity product = ProductEntity.builder().status(ProductStatus.ACTIVE).basePrice(new BigDecimal("10")).moq(1).titleZh("p").build();
        product.setId(productId);
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(variantRepository.findById(staleVariantId)).thenReturn(Optional.empty());

        var req = new CreateOrderRequest("EXT", new AddressInput("X", null, null, "L1", null, "C", null, "00000", "ES"),
                null, List.of(new OrderItemInput(productId, staleVariantId, 1)), null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> orderUseCase.createOrder(UUID.randomUUID(), null, req))
                .isInstanceOfSatisfying(NotFoundException.class, ex -> {
                    org.assertj.core.api.Assertions.assertThat(ex.getCode()).isEqualTo("CART_ITEM_UNAVAILABLE");
                    org.assertj.core.api.Assertions.assertThat(ex.getDetail()).containsExactly(staleVariantId.toString());
                });
    }

    @Test
    void forwardOrder_setsForwardedAndPublishesWebhook() {
        UUID id = UUID.randomUUID();
        Order order = Order.builder().status(OrderStatus.PAID).build();
        order.setId(id);
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        orderUseCase.forwardOrder(id);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FORWARDED);
        verify(orderRepository).save(order);
        verify(webhooks).publish(org.mockito.ArgumentMatchers.eq("order.forwarded"),
                org.mockito.ArgumentMatchers.eq(id.toString()), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void refundOrder_creditsWalletAndMarksRefunded() {
        UUID id = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        Order order = Order.builder().status(OrderStatus.PAID).userId(buyer).orderNumber("NX-1").totalCents(2500)
                .build();
        order.setId(id);
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        orderUseCase.refundOrder(id);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        verify(walletUseCase).deposit(org.mockito.ArgumentMatchers.eq(buyer),
                org.mockito.ArgumentMatchers.eq(2500L), org.mockito.ArgumentMatchers.eq(id),
                org.mockito.ArgumentMatchers.eq("refund-" + id), org.mockito.ArgumentMatchers.anyString());
    }

    // ---------------- cancelación por el cliente (cancelMyOrder) ----------------

    @Test
    void cancelMyOrder_toWallet_refundsWalletAndCancels() {
        UUID id = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        Order order = Order.builder().status(OrderStatus.PAID).userId(buyer).orderNumber("NX-9").totalCents(1500)
                .build();
        order.setId(id);
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        orderUseCase.cancelMyOrder(buyer, id, true); // reembolso a la wallet (inmediato)

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(walletUseCase).deposit(org.mockito.ArgumentMatchers.eq(buyer),
                org.mockito.ArgumentMatchers.eq(1500L), org.mockito.ArgumentMatchers.eq(id),
                org.mockito.ArgumentMatchers.eq("cancel-" + id), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void cancelMyOrder_toOriginal_refundsCardViaProvider() {
        UUID id = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Order order = Order.builder().status(OrderStatus.PAID).userId(buyer).orderNumber("NX-8").totalCents(4000)
                .build();
        order.setId(id);
        com.nexaplatform.dropshipping.domain.model.Payment card =
                com.nexaplatform.dropshipping.domain.model.Payment.builder().id(paymentId)
                        .status(com.nexaplatform.dropshipping.domain.enums.PaymentStatus.SUCCEEDED)
                        .provider("stripe").build();
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentUseCase.listOrderPayments(id)).thenReturn(List.of(card));

        orderUseCase.cancelMyOrder(buyer, id, false); // reembolso al método original (tarjeta → Stripe)

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(paymentUseCase).refundOrderPayment(id, paymentId, 0);
        verify(walletUseCase, never()).deposit(any(), org.mockito
                .ArgumentMatchers.anyLong(), any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void cancelMyOrder_rejectsWhenNotOwner() {
        UUID id = UUID.randomUUID();
        Order order = Order.builder().status(OrderStatus.PAID).userId(UUID.randomUUID()).build();
        order.setId(id);
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));

        UUID otroUsuario = UUID.randomUUID();
        assertThatThrownBy(() -> orderUseCase.cancelMyOrder(otroUsuario, id, true))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void cancelMyOrder_rejectsWhenAlreadyForwarded() {
        UUID id = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        Order order = Order.builder().status(OrderStatus.FORWARDED).userId(buyer).build();
        order.setId(id);
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderUseCase.cancelMyOrder(buyer, id, true))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void cancelMyOrder_rejectsWhenSupplierAlreadyBought() {
        // Lo que se vio el 20-ago-2026: el tablero de compras marcaba «COMPRADO» y el pedido seguía
        // ofreciendo «Cancelar pedido», porque el estado del PEDIDO sigue siendo PAGADO hasta que salen
        // TODAS las compras. Cancelar ahí devuelve el dinero al cliente con el género ya pagado en 1688:
        // la pérdida es íntegra y no hay a quién reclamarla.
        UUID id = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        Order order = Order.builder().status(OrderStatus.PAID).userId(buyer).orderNumber("NX-6").totalCents(1500)
                .build();
        order.setId(id);
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(supplierPurchaseService.anyBought(id)).thenReturn(true);

        assertThatThrownBy(() -> orderUseCase.cancelMyOrder(buyer, id, true))
                .isInstanceOf(BusinessException.class)
                // El CÓDIGO es el contrato: es lo que el front traduce al idioma del comprador.
                .hasFieldOrPropertyWithValue("code", "ORDER_NOT_CANCELLABLE")
                .hasMessageContaining("already purchased");

        assertThat(order.getStatus()).as("el pedido no se toca").isEqualTo(OrderStatus.PAID);
        verify(walletUseCase, never()).deposit(any(), org.mockito.ArgumentMatchers.anyLong(), any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void cancelMyOrder_allowsWhenNothingBoughtYet() {
        // Mientras la compra siga en «por comprar» no se ha gastado nada: el cliente puede echarse atrás.
        UUID id = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        Order order = Order.builder().status(OrderStatus.PAID).userId(buyer).orderNumber("NX-5").totalCents(1500)
                .build();
        order.setId(id);
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(supplierPurchaseService.anyBought(id)).thenReturn(false);

        orderUseCase.cancelMyOrder(buyer, id, true);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancelMyOrder_retiraLasComprasDelTablero() {
        // El tablero de compras se pinta por el estado de la COMPRA, no por el del pedido: si al
        // cancelar no se retiran, el admin sigue viendo la tarjeta y compra en 1688 género de una venta
        // que ya no existe.
        UUID id = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        Order order = Order.builder().status(OrderStatus.PAID).userId(buyer).orderNumber("NX-8").totalCents(1500)
                .build();
        order.setId(id);
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(supplierPurchaseService.anyBought(id)).thenReturn(false);

        orderUseCase.cancelMyOrder(buyer, id, true);

        verify(supplierPurchaseService).cancelUnbought(org.mockito.ArgumentMatchers.eq(id),
                org.mockito.ArgumentMatchers.anyString());
    }

    // ---------------- cancelación por el admin (cancelOrder): reembolsa si estaba pagado ----------------

    @Test
    void cancelOrder_refundsWhenOrderWasPaid() {
        UUID id = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        Order order = Order.builder().status(OrderStatus.PAID).userId(buyer).orderNumber("NX-7").totalCents(3000)
                .build();
        order.setId(id);
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        orderUseCase.cancelOrder(id);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(walletUseCase).deposit(org.mockito.ArgumentMatchers.eq(buyer),
                org.mockito.ArgumentMatchers.eq(3000L), org.mockito.ArgumentMatchers.eq(id),
                org.mockito.ArgumentMatchers.eq("cancel-" + id), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void cancelOrder_noRefundWhenUnpaid() {
        UUID id = UUID.randomUUID();
        Order order = Order.builder().status(OrderStatus.PENDING).userId(UUID.randomUUID()).totalCents(1000).build();
        order.setId(id);
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        orderUseCase.cancelOrder(id);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        org.mockito.Mockito.verifyNoInteractions(walletUseCase);
    }

    /* ======================= pedido mínimo por producto (lote mixto) ======================= */

    /**
     * Producto con el mínimo que se le indique, listo para pedir.
     *
     * <p>Los dobles van en modo indulgente porque los casos de rechazo cortan antes de tarificar ni
     * guardar: exigir que se usen convertiría «se rechazó pronto» en un fallo del test.
     */
    private UUID productoConMoq(int moq) {
        UUID id = UUID.randomUUID();
        ProductEntity product = ProductEntity.builder().status(ProductStatus.ACTIVE)
                .basePrice(new BigDecimal("10.00")).moq(moq).titleZh("Lote").build();
        product.setId(id);
        lenient().when(productRepository.findById(id)).thenReturn(Optional.of(product));
        lenient().when(pricingService.priceFor(any(), any())).thenReturn(priced("10.00"));
        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        return id;
    }

    /** Variante comprable de un producto, para componer lotes mixtos. */
    private UUID varianteDe(String sku) {
        UUID id = UUID.randomUUID();
        ProductVariantEntity variant = ProductVariantEntity.builder().price(new BigDecimal("10"))
                .sku(sku).stock(10).active(true).build();
        variant.setId(id);
        lenient().when(variantRepository.findById(id)).thenReturn(Optional.of(variant));
        return id;
    }

    private CreateOrderRequest pedidoCon(List<OrderItemInput> items) {
        return new CreateOrderRequest("EXT-MOQ",
                new AddressInput("John Doe", "555", "j@x.com", "Line 1", null, "Madrid", "M", "28001", "ES"),
                null, items, null);
    }

    /**
     * El mínimo se cumple sumando variantes distintas, como el lote mixto de 1688.
     *
     * <p>Dos colores de una unidad cada uno cubren un mínimo de dos: al proveedor le da igual el reparto,
     * solo mira el total. Exigirlo por variante obligaría a comprar el doble de lo necesario.
     */
    @Test
    void elMinimoSeCumpleMezclandoVariantes() {
        UUID producto = productoConMoq(2);

        Order order = orderUseCase.createOrder(UUID.randomUUID(), null, pedidoCon(List.of(
                new OrderItemInput(producto, varianteDe("ROJO-M"), 1),
                new OrderItemInput(producto, varianteDe("AZUL-L"), 1))));

        assertThat(order.getItems()).hasSize(2);
    }

    /** Justo en el mínimo con una sola línea: pasa. Es el borde exacto. */
    @Test
    void elMinimoJustoEnElBordeSeAcepta() {
        UUID producto = productoConMoq(3);

        Order order = orderUseCase.createOrder(UUID.randomUUID(), null,
                pedidoCon(List.of(new OrderItemInput(producto, null, 3))));

        assertThat(order.getItems()).hasSize(1);
    }

    /** Una unidad por debajo del mínimo: se rechaza antes de cobrar. */
    @Test
    void unaUnidadPorDebajoDelMinimoSeRechaza() {
        UUID producto = productoConMoq(3);

        assertThatThrownBy(() -> orderUseCase.createOrder(UUID.randomUUID(), null,
                pedidoCon(List.of(new OrderItemInput(producto, null, 2)))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("mínimo");
    }

    /**
     * El mínimo es de cada producto por separado.
     *
     * <p>Dos productos distintos con una unidad cada uno suman dos, pero ninguno llega a su propio
     * mínimo: contar el total del pedido dejaría pasar compras que el proveedor rechaza.
     */
    @Test
    void elMinimoNoSeCompartEntreProductosDistintos() {
        UUID uno = UUID.randomUUID();
        UUID otro = UUID.randomUUID();
        for (UUID id : List.of(uno, otro)) {
            ProductEntity p = ProductEntity.builder().status(ProductStatus.ACTIVE)
                    .basePrice(new BigDecimal("10.00")).moq(2).titleZh("Lote").build();
            p.setId(id);
            lenient().when(productRepository.findById(id)).thenReturn(Optional.of(p));
        }
        lenient().when(pricingService.priceFor(any(), any())).thenReturn(priced("10.00"));
        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> orderUseCase.createOrder(UUID.randomUUID(), null, pedidoCon(List.of(
                new OrderItemInput(uno, null, 1),
                new OrderItemInput(otro, null, 1)))))
                .isInstanceOf(BusinessException.class);
    }

    /** Un producto sin mínimo (moq = 1) no impone nada: una unidad basta. */
    @Test
    void sinMinimoUnaUnidadBasta() {
        UUID producto = productoConMoq(1);

        Order order = orderUseCase.createOrder(UUID.randomUUID(), null,
                pedidoCon(List.of(new OrderItemInput(producto, null, 1))));

        assertThat(order.getItems()).hasSize(1);
    }

    /** Pasarse del mínimo no es problema: el mínimo es suelo, no cupo. */
    @Test
    void pasarseDelMinimoSeAcepta() {
        UUID producto = productoConMoq(2);

        Order order = orderUseCase.createOrder(UUID.randomUUID(), null,
                pedidoCon(List.of(new OrderItemInput(producto, null, 50))));

        assertThat(order.getItems()).hasSize(1);
    }
}
