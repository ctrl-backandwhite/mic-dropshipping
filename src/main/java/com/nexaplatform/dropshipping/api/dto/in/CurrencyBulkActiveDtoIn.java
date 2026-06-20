package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.List;

/** Payload para activar/desactivar varias monedas a la vez. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CurrencyBulkActiveDtoIn {

    @NotNull
    @Schema(description = "Códigos ISO de las monedas a cambiar", example = "[\"EUR\",\"USD\"]")
    private List<String> codes;

    @Schema(description = "Nuevo estado activo", example = "true")
    private boolean active;
}
