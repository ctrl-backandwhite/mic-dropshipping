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

    /**
     * Importe YA formateado en la divisa en que se emitió la factura ("99,00 €", "￥5.000"). El frontend
     * SÓLO pinta esta cadena: ningún cálculo ni formateo de precio vive en el cliente.
     *
     * <p>Lo componía el navegador con {@code (total / 100)} más el código de divisa, y en las divisas sin
     * céntimos (yen, won) Stripe manda unidades enteras, así que la factura salía cien veces más barata.
     */
    @Schema(description = "Importe total ya formateado en la divisa de la factura, listo para pintar")
    private String totalFormatted;

    @Schema(description = "Estado: paid / open / void / ...")
    private String status;

    @Schema(description = "Fecha (epoch segundos)")
    private Long created;

    @Schema(description = "URL del PDF de la factura")
    private String pdfUrl;

    @Schema(description = "URL de la factura alojada en Stripe")
    private String hostedUrl;
}
