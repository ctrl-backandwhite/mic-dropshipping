package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Update en lote de las dos bolsas de subvención por producto (1-sep-2026).
 *
 * <p>Se pueden fijar para UN producto, para TODOS los de una categoría o para TODO el catálogo:
 * <ul>
 *   <li>{@code productIds} presente → solo esos productos.</li>
 *   <li>{@code categoryId} presente (sin productIds) → todos los de esa categoría.</li>
 *   <li>ninguno de los dos → todo el catálogo.</li>
 * </ul>
 *
 * <p>Cada bolsa subvenciona <b>una sola cosa</b>: la del porte cubre porte y la del arancel cubre
 * arancel, sin trasvase. Un campo nulo deja esa bolsa como estaba —así se puede tocar una sin pisar la
 * otra—; el valor 0 la elimina.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminSubsidyBulkDtoIn {

    @Schema(description = "Bolsa de subvención del porte en CNY (0 = sin subvención; ausente = no tocar)")
    private BigDecimal shippingUserCny;

    @Schema(description = "Bolsa de subvención del arancel en CNY (0 = sin subvención; ausente = no tocar)")
    private BigDecimal dutyUserCny;

    @Schema(description = "Productos concretos a actualizar (opcional; si viene, manda sobre categoryId)")
    private List<UUID> productIds;

    @Schema(description = "Categoría cuyos productos se actualizan (opcional; solo si productIds está vacío)")
    private UUID categoryId;
}
