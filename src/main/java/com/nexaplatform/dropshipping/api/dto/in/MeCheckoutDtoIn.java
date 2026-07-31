package com.nexaplatform.dropshipping.api.dto.in;

import com.nexaplatform.dropshipping.api.dto.PartnerDtos.AddressInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Checkout payload for the authenticated user's cart.
 *
 * <p>DROP-549: {@code paymentMethod} is one of WALLET | CARD | PAYPAL | USDT.
 * When WALLET, the wallet is charged immediately; otherwise the order is left
 * PENDING and the client completes payment via {@code /payment-intent}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MeCheckoutDtoIn {

    private UUID shippingAddressId;

    @Valid
    private AddressInput shippingAddressInline;

    // @Valid CASCADA a cada Item: sin él, las constraints de Item (@Positive, @NotNull) NO se aplicaban
    // y llegaban cantidades negativas/enormes a la lógica de negocio. @Size acota el nº de líneas.
    @NotEmpty
    @Size(max = 100, message = "Demasiadas líneas en el pedido")
    private List<@Valid Item> items;

    private String notes;

    private String paymentMethod;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        @NotNull
        private UUID productId;
        private UUID variantId;
        @Positive
        @Max(value = 100_000, message = "Cantidad por línea demasiado alta")
        private int quantity;
    }
}
