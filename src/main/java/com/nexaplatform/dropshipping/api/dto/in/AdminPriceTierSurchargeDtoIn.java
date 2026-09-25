package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * El recargo fijo de UN tramo de cantidad (23-sep-2026).
 *
 * <p>Hasta ahora el recargo era uno solo para todo el producto, y se cobraba igual a quien se lleva
 * una unidad que a quien se lleva diez mil. Pero lo que cubre —la gestión de la compra, el
 * manipulado, la parte fija del despacho— no escala con la cantidad, así que repetirlo encarecía el
 * pedido grande justo donde la tabla de cantidades promete lo contrario. El envío y el arancel
 * siguen siendo uno por producto: esos sí escalan con el bulto.
 *
 * <p>Nulo NO es cero, y por eso el campo se admite vacío: nulo devuelve el tramo a heredar el
 * recargo del producto, y cero es un recargo de cero que alguien ha decidido escribir.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPriceTierSurchargeDtoIn {

    @PositiveOrZero
    @Schema(description = "Recargo del tramo en % sobre el coste. Vacío = el tramo hereda el del producto")
    private BigDecimal surchargePct;
}
