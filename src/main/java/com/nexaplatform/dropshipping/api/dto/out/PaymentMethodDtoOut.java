package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Tarjeta guardada del usuario (datos no sensibles que devuelve Stripe; nunca el PAN completo). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentMethodDtoOut {

    @Schema(description = "Id del PaymentMethod en Stripe (pm_...)")
    private String id;

    @Schema(description = "Marca de la tarjeta (visa, mastercard, ...)")
    private String brand;

    @Schema(description = "Últimos 4 dígitos")
    private String last4;

    @Schema(description = "Mes de expiración")
    private Long expMonth;

    @Schema(description = "Año de expiración")
    private Long expYear;

    @Schema(description = "true si es la tarjeta por defecto (la que cobra las suscripciones)")
    private boolean isDefault;
}
