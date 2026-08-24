package com.nexaplatform.dropshipping.api.dto.out;

import java.time.Instant;
import java.util.UUID;

/**
 * Un grupo de declaración tal y como lo ve el panel de aprobación.
 *
 * @param approved   es lo único que decide si el grupo agrupa; mientras sea {@code false} cada producto
 *                   de la terna sigue siendo su propia línea de declaración
 * @param approvedBy quién firmó el texto: cuando una aduana discrepe, hay a quién preguntar
 */
public record AdminDeclarationGroupDtoOut(UUID id, String hs6, String material, String usageCode, String ename,
        String cname, Integer productCount, boolean approved, Instant approvedAt, String approvedBy) {
}
