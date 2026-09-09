package com.nexaplatform.dropshipping.api.dto.out;

import java.time.Instant;
import java.util.UUID;

/**
 * Un grupo de declaración tal y como lo ve el panel de aprobación.
 *
 * @param approved   es lo único que decide si el grupo agrupa; mientras sea {@code false} cada producto
 *                   de la terna sigue siendo su propia línea de declaración
 * @param approvedBy quién firmó el texto: cuando una aduana discrepe, hay a quién preguntar
 * @param sinRedactar la descripción sigue siendo el relleno con el que nació el grupo —el número de la
 *                   partida y nada más—, así que no se puede firmar hasta escribirla. Viaja como dato
 *                   propio y no se deduce en el navegador: la regla de qué es relleno vive junto a lo
 *                   que lo genera, y deducirla dos veces es garantizar que un día digan cosas distintas
 */
public record AdminDeclarationGroupDtoOut(UUID id, String hs6, String material, String usageCode, String ename,
        String cname, Integer productCount, boolean approved, Instant approvedAt, String approvedBy,
        boolean sinRedactar) {
}
