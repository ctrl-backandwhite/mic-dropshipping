package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * DROP-158 (30-ago-2026): update del recargo fijo por producto (surcharge_cny) en lote.
 *
 * <p>El recargo se puede fijar a un valor para UN producto, para TODOS los de una categoría o para
 * TODO el catálogo:
 * <ul>
 *   <li>{@code productIds} presente → solo esos productos (edición por producto).</li>
 *   <li>{@code categoryId} presente (sin productIds) → todos los productos de esa categoría.</li>
 *   <li>ninguno de los dos → todo el catálogo (update masivo global).</li>
 * </ul>
 * El valor 0 elimina el recargo (vuelve al default de carga).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminSurchargeBulkDtoIn {

    @NotNull
    @Schema(description = "Recargo en % sobre el coste a aplicar (0 = sin recargo)")
    private BigDecimal surchargePct;

    @Schema(description = "Productos concretos a actualizar (opcional; si viene, manda sobre categoryId)")
    private List<UUID> productIds;

    @Schema(description = "Categoría cuyos productos se actualizan (opcional; solo si productIds está vacío)")
    private UUID categoryId;
}
