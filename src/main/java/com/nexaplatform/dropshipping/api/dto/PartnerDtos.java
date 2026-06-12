package com.nexaplatform.dropshipping.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PartnerDtos {
    private PartnerDtos() {
    }

    /* ============ Shop sync ============ */

    public record ConnectShopRequest(@NotBlank String platform, @NotBlank String shopHandle,
            @NotBlank String accessToken) {
    }

    public record ShopConnectionView(UUID id, String platform, String shopHandle, boolean active, Instant connectedAt) {
    }

    public record SyncProductRequest(@NotNull UUID productId) {
    }

    public record SyncResultView(UUID productId, String remoteProductId, String status, String message) {
    }

    /* ============ Orders ============ */

    public record AddressInput(@NotBlank String fullName, String phone, String email, @NotBlank String line1,
            String line2, @NotBlank String city, String state, String postalCode, @NotBlank String country) {
    }

    public record OrderItemInput(@NotNull UUID productId, UUID variantId, @Positive int quantity) {
    }

    public record CreateOrderRequest(String externalOrderId, @Valid @NotNull AddressInput shippingAddress,
            @Valid AddressInput billingAddress, @NotEmpty List<@Valid OrderItemInput> items, String notes) {
    }

    public record OrderItemView(UUID productId, UUID variantId, int quantity, BigDecimal unitPrice,
            BigDecimal lineTotal) {
    }

    public record OrderView(UUID id, String orderNumber, String status, BigDecimal subtotal, BigDecimal shipping,
            BigDecimal tax, BigDecimal total, String currency, Instant placedAt, Instant shippedAt,
            List<OrderItemView> items) {
    }
}
