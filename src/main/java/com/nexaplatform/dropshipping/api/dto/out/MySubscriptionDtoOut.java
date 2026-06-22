package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Suscripción vigente del usuario (para la sección "Mi plan" del perfil). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MySubscriptionDtoOut {

    @Schema(description = "Id del plan (coincide con el id de la lista pública de planes)")
    private String planId;

    @Schema(description = "Estado: ACTIVE / TRIALING / PAST_DUE / CANCELED / INCOMPLETE / PAUSED")
    private String status;

    @Schema(description = "MONTHLY o YEARLY")
    private String billingPeriod;

    @Schema(description = "Fin del periodo actual (próxima renovación)")
    private Instant currentPeriodEnd;

    @Schema(description = "Fecha en la que se cancelará (si está marcada para cancelar al final del periodo)")
    private Instant cancelAt;
}
