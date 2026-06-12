package com.nexaplatform.dropshipping.api.dto.in;

import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payment-intent request shared by the partner (B2B) and customer (B2C) order
 * payment endpoints. {@code WALLET} is handled separately from the external
 * provider methods (CARD/PAYPAL/USDT).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderPaymentIntentDtoIn {

    public enum Method {
        WALLET, CARD, PAYPAL, USDT
    }

    @NotNull
    private Method method;

    /** True when the wallet should be charged atomically instead of an external provider. */
    public boolean isWallet() {
        return method == Method.WALLET;
    }

    /** Map the external-provider methods to the domain enum (WALLET handled separately). */
    public PaymentMethod toPaymentMethod() {
        return switch (method) {
            case CARD -> PaymentMethod.CARD;
            case PAYPAL -> PaymentMethod.PAYPAL;
            case USDT -> PaymentMethod.USDT;
            case WALLET -> throw new IllegalStateException("WALLET handled separately");
        };
    }
}
