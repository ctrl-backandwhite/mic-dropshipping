package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * El proveedor ha despachado el bulto hacia el almacén chino.
 *
 * <p>El seguimiento es obligatorio: es el único dato por el que el almacén empareja el paquete físico
 * con las instrucciones de re-empaquetado. Sin él, el bulto llega como 滞留件 y se destruye a los 30 días.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPurchaseShippedDtoIn {

    @NotBlank
    @Schema(description = "Número de seguimiento nacional chino que da el proveedor")
    private String domesticTracking;

    @Schema(description = "Transportista chino (SF, JT, YTO...)")
    private String domesticCarrier;
}
