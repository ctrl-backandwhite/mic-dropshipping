package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Configuración pública de billing para el frontend (Stripe Elements). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingConfigDtoOut {

    @Schema(description = "Clave pública de Stripe (pk_test/pk_live) para inicializar Stripe Elements")
    private String publishableKey;

    @Schema(description = "true si los pagos con Stripe están activos en este entorno")
    private boolean enabled;

    @Schema(description = "true si el usuario ya usó su prueba gratis de 15 días (no puede volver a activarla)")
    private boolean freeTrialUsed;
}
