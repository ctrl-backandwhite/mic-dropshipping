package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Factura del historial de facturación del usuario (de Stripe). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingInvoiceDtoOut {

    @Schema(description = "Número de factura de Stripe")
    private String number;

    @Schema(description = "Importe total en céntimos (moneda de cobro)")
    private Long total;

    @Schema(description = "Moneda de cobro")
    private String currency;

    @Schema(description = "Estado: paid / open / void / ...")
    private String status;

    @Schema(description = "Fecha (epoch segundos)")
    private Long created;

    @Schema(description = "URL del PDF de la factura")
    private String pdfUrl;

    @Schema(description = "URL de la factura alojada en Stripe")
    private String hostedUrl;
}
