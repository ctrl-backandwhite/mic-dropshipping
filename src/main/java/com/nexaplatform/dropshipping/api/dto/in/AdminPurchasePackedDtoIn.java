package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Confirmación de que la orden de re-empaquetado ya está dada de alta en el OMS de Yunfulfillment. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPurchasePackedDtoIn {

    @Schema(description = "Número de la orden de re-empaquetado que devuelve el OMS")
    private String packOrderNo;

    @Schema(description = "Servicio contratado: REPACKAGING, CONSOLIDATE, CUSTOM...")
    private String serviceType;
}
