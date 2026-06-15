package com.nexaplatform.dropshipping.api.dto.in;

import com.nexaplatform.dropshipping.api.dto.PartnerDtos.AddressInput;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.CreateOrderRequest;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.OrderItemInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * DROP-690: payload for admin manual order creation (and one row of the bulk import). Wraps the
 * core {@link CreateOrderRequest} adding the buyer email (the admin types the customer; the partner
 * flow resolved it from the JWT). {@code customerEmail} is optional — a guest order is allowed.
 */
public record AdminCreateOrderDtoIn(String customerEmail, String externalOrderId,
        @Valid @NotNull AddressInput shippingAddress, @Valid AddressInput billingAddress,
        @NotEmpty List<@Valid OrderItemInput> items, String notes) {

    /** Projects the admin DTO onto the shared core request consumed by the use case. */
    public CreateOrderRequest toCreateOrderRequest() {
        return new CreateOrderRequest(externalOrderId, shippingAddress, billingAddress, items, notes);
    }
}
