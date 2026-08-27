package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Datos que teclea el admin tras pagar el pedido en 1688 con Alipay. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPurchaseBoughtDtoIn {

    @Schema(description = "Número del pedido en 1688")
    private String purchaseRef;

    @Schema(description = "Coste REAL pagado por la mercancía, en CNY")
    private BigDecimal costCny;

    @Schema(description = "Envío nacional chino pagado al proveedor, en CNY")
    private BigDecimal shippingCny;
}
