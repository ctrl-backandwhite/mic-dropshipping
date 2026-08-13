package com.nexaplatform.dropshipping.api.dto.in;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Petición para cobrar un pedido con una tarjeta GUARDADA del usuario (off-session).
 * {@code paymentMethodId} es el id de Stripe (pm_...) de una tarjeta que pertenece al Customer del usuario.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SavedCardPayDtoIn {

    @NotBlank
    private String paymentMethodId;
}
