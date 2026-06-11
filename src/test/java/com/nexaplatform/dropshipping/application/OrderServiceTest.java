package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.PartnerDtos.AddressInput;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.CreateOrderRequest;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.OrderItemInput;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.service.OrderService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AddressEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerOrderEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.api.mapper.AdminOrderMapper;
import com.nexaplatform.dropshipping.application.notifications.NotificationsPublisher;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.AddressRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderRepository;
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
class OrderServiceTest {

    @Mock OrderRepository orderRepository;
    @Mock AddressRepository addressRepository;
    @Mock ProductRepository productRepository;
    @Mock ProductVariantRepository variantRepository;
    @Mock UserRepository userRepository;
    @Mock ShopConnectionRepository shopConnectionRepository;
    @Mock UserAddressRepository userAddressRepository;
    @Mock AdminOrderMapper adminOrderMapper;
    @Mock com.nexaplatform.dropshipping.api.mapper.PartnerOrderDtoMapper partnerOrderDtoMapper;
    @Mock com.nexaplatform.dropshipping.application.service.WebhookDispatcherService webhooks;
    @Mock com.nexaplatform.dropshipping.application.service.WalletService walletService;
    @Mock NotificationsPublisher notificationsPublisher;

    OrderService orderService;

    @BeforeEach
    void setup() {
        orderService = new OrderService(orderRepository, addressRepository, productRepository, variantRepository,
                userRepository, shopConnectionRepository, userAddressRepository, adminOrderMapper,
                partnerOrderDtoMapper, webhooks, walletService, notificationsPublisher);
    }

    @Test
    void create_order_with_two_items_computes_totals() {
        UUID productId = UUID.randomUUID();
        ProductEntity product = ProductEntity.builder()
                .basePrice(new BigDecimal("12.50"))
                .moq(1)
                .titleZh("Widget")
                .build();
        product.setId(productId);
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(addressRepository.save(any(AddressEntity.class))).thenAnswer(inv -> {
            AddressEntity a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });
        when(orderRepository.save(any(CustomerOrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var req = new CreateOrderRequest(
                "EXT-001",
                new AddressInput("John Doe", "555", "j@x.com", "Line 1", null, "Madrid", "M", "28001", "ES"),
                null,
                List.of(new OrderItemInput(productId, null, 3)),
                null);

        var view = orderService.createOrder(UUID.randomUUID(), null, req);

        assertThat(view.items()).hasSize(1);
        assertThat(view.subtotal()).isEqualByComparingTo("37.50"); // 12.50 * 3
        assertThat(view.total()).isEqualByComparingTo("37.50");
        assertThat(view.status()).isEqualTo("PENDING");
        assertThat(view.orderNumber()).startsWith("NX-");
    }

    @Test
    void create_order_fails_if_product_has_no_price() {
        UUID productId = UUID.randomUUID();
        ProductEntity product = ProductEntity.builder().titleZh("noprice").moq(1).build();
        product.setId(productId);
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(addressRepository.save(any(AddressEntity.class))).thenAnswer(inv -> {
            AddressEntity a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });

        var req = new CreateOrderRequest(
                "EXT", new AddressInput("X", null, null, "L1", null, "C", null, "00000", "ES"),
                null, List.of(new OrderItemInput(productId, null, 1)), null);

        assertThatThrownBy(() -> orderService.createOrder(UUID.randomUUID(), null, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no price");
    }

    @Test
    void create_order_fails_if_product_missing() {
        UUID productId = UUID.randomUUID();
        when(productRepository.findById(productId)).thenReturn(Optional.empty());
        when(addressRepository.save(any(AddressEntity.class))).thenAnswer(inv -> {
            AddressEntity a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });

        var req = new CreateOrderRequest(
                "EXT", new AddressInput("X", null, null, "L1", null, "C", null, "00000", "ES"),
                null, List.of(new OrderItemInput(productId, null, 1)), null);

        assertThatThrownBy(() -> orderService.createOrder(UUID.randomUUID(), null, req))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void variant_price_overrides_product_price() {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        ProductEntity product = ProductEntity.builder()
                .basePrice(new BigDecimal("10")).moq(1).titleZh("p").build();
        product.setId(productId);
        ProductVariantEntity variant = ProductVariantEntity.builder()
                .price(new BigDecimal("15")).sku("V1").stock(10).active(true).build();
        variant.setId(variantId);

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(variantRepository.findById(variantId)).thenReturn(Optional.of(variant));
        when(addressRepository.save(any(AddressEntity.class))).thenAnswer(inv -> {
            AddressEntity a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });
        when(orderRepository.save(any(CustomerOrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var req = new CreateOrderRequest(
                "EXT", new AddressInput("X", null, null, "L1", null, "C", null, "00000", "ES"),
                null, List.of(new OrderItemInput(productId, variantId, 2)), null);

        var v = orderService.createOrder(UUID.randomUUID(), null, req);
        assertThat(v.subtotal()).isEqualByComparingTo("30");
    }

    @Test
    void forwardOrder_setsForwardedAndPublishesWebhook() {
        UUID id = UUID.randomUUID();
        var order = CustomerOrderEntity.builder()
                .status(com.nexaplatform.dropshipping.domain.enums.OrderStatus.PAID).build();
        order.setId(id);
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(adminOrderMapper.toRow(order)).thenReturn(
                com.nexaplatform.dropshipping.api.dto.out.AdminOrderRowDtoOut.builder().id(id).build());

        orderService.forwardOrder(id);

        assertThat(order.getStatus()).isEqualTo(com.nexaplatform.dropshipping.domain.enums.OrderStatus.FORWARDED);
        org.mockito.Mockito.verify(orderRepository).save(order);
        org.mockito.Mockito.verify(webhooks).publish(
                org.mockito.ArgumentMatchers.eq("order.forwarded"),
                org.mockito.ArgumentMatchers.eq(id.toString()),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void refundOrder_creditsWalletAndMarksRefunded() {
        UUID id = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        var order = CustomerOrderEntity.builder()
                .status(com.nexaplatform.dropshipping.domain.enums.OrderStatus.PAID)
                .userId(buyer).orderNumber("NX-1").build();
        order.setId(id);
        order.setTotalCents(2500);
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(adminOrderMapper.toRow(order)).thenReturn(
                com.nexaplatform.dropshipping.api.dto.out.AdminOrderRowDtoOut.builder().id(id).build());

        orderService.refundOrder(id);

        assertThat(order.getStatus()).isEqualTo(com.nexaplatform.dropshipping.domain.enums.OrderStatus.REFUNDED);
        org.mockito.Mockito.verify(walletService).deposit(
                org.mockito.ArgumentMatchers.eq(buyer), org.mockito.ArgumentMatchers.eq(2500L),
                org.mockito.ArgumentMatchers.eq(id), org.mockito.ArgumentMatchers.eq("refund-" + id),
                org.mockito.ArgumentMatchers.anyString());
    }
}
