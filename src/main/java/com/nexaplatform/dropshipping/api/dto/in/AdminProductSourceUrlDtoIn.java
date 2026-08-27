package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Corrección manual del enlace a la ficha del proveedor desde el PDP (solo admin).
 *
 * <p>Sin {@code @NotBlank} a propósito: el vacío lo rechaza la propia regla de negocio para que el admin
 * reciba el mismo mensaje traducido que cuando el dominio no está permitido, y no un 400 genérico.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminProductSourceUrlDtoIn {

    @Schema(description = "Enlace http(s) a la ficha del producto en 1688 o Alibaba")
    private String sourceUrl;
}
