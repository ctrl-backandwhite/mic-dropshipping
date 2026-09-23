package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** Estado de la sincronización de divisas: última ejecución, próxima programada y el cron en uso. */
public record CurrencySyncStatusDtoOut(
        @Schema(description = "Instante de la última sincronización (UTC); null si nunca se sincronizó") Instant lastSyncedAt,
        @Schema(description = "Próxima ejecución programada (UTC) según el cron; null si el cron es inválido") Instant nextSyncAt,
        @Schema(description = "Expresión cron vigente del scheduler de divisas") String cron) {
}
