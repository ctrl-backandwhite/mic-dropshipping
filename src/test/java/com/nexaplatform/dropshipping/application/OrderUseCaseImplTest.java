package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.PartnerDtos.AddressInput;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.CreateOrderRequest;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.OrderItemInput;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.notifications.NotificationsPublisher;
import com.nexaplatform.dropshipping.application.service.AffiliateProgramService;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.WebhookDispatcherService;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.application.usecase.impl.OrderUseCaseImpl;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ShopConnectionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserAddressRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderUseCaseImplTest {

    @Mock
    com.nexaplatform.dropshipping.domain.repository.OrderRepository orderRepository;
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
    com.nexaplatform.dropshipping.application.usecase.PaymentUseCase paymentUseCase;

    OrderUseCaseImpl orderUseCase;

    @BeforeEach
    void setup() {
        orderUseCase = new OrderUseCaseImpl(orderRepository, productRepository, variantRepository, userRepository,
                shopConnectionRepository, userAddressRepository, webhooks, walletUseCase, notificationsPublisher,
                pricingService, affiliateProgramService, paymentUseCase);
    }

    /** DROP-637: the checkout now bills the priced amount (retailUsd) from PricingService. */
    private static PricingService.PricedAmount priced(String retail) {
        BigDecimal r = retail == null ? null : new BigDecimal(retail);
        return new PricingService.PricedAmount(r, r, r, "USD", "$", null, BigDecimal.ZERO);
    }

    @Test
    void create_order_with_two_items_computes_totals() {
        UUID productId = UUID.randomUUID();
        ProductEntity product = ProductEntity.builder().basePrice(new BigDecimal("12.50")).moq(1).titleZh("Widget")
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

    @Test
    void create_order_fails_if_product_has_no_price() {
        UUID productId = UUID.randomUUID();
        ProductEntity product = ProductEntity.builder().titleZh("noprice").moq(1).build();
        product.setId(productId);
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(pricingService.priceFor(any(), any())).thenReturn(priced(null));

        var req = new CreateOrderRequest("EXT", new AddressInput("X", null, null, "L1", null, "C", null, "00000", "ES"),
                null, List.of(new OrderItemInput(productId, null, 1)), null);

        assertThatThrownBy(() -> orderUseCase.createOrder(UUID.randomUUID(), null, req))
                .isInstanceOf(BusinessException.class).hasMessageContaining("no price");
    }

    @Test
    void create_order_fails_if_product_missing() {
        UUID productId = UUID.randomUUID();
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        var req = new CreateOrderRequest("EXT", new AddressInput("X", null, null, "L1", null, "C", null, "00000", "ES"),
                null, List.of(new OrderItemInput(productId, null, 1)), null);

        assertThatThrownBy(() -> orderUseCase.createOrder(UUID.randomUUID(), null, req))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void variant_price_overrides_product_price() {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        ProductEntity product = ProductEntity.builder().basePrice(new BigDecimal("10")).moq(1).titleZh("p").build();
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
    void forwardOrder_setsForwardedAndPublishesWebhook() {
        UUID id = UUID.randomUUID();
        Order order = Order.builder().status(OrderStatus.PAID).build();
        order.setId(id);
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        orderUseCase.forwardOrder(id);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FORWARDED);
        org.mockito.Mockito.verify(orderRepository).save(order);
        org.mockito.Mockito.verify(webhooks).publish(org.mockito.ArgumentMatchers.eq("order.forwarded"),
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
        org.mockito.Mockito.verify(walletUseCase).deposit(org.mockito.ArgumentMatchers.eq(buyer),
                org.mockito.ArgumentMatchers.eq(2500L), org.mockito.ArgumentMatchers.eq(id),
                org.mockito.ArgumentMatchers.eq("refund-" + id), org.mockito.ArgumentMatchers.anyString());
    }
}
