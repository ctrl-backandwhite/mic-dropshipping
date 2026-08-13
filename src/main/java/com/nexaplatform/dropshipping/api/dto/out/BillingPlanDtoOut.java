package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Public subscription plan projection exposed by the storefront billing API.
 * Replaces the legacy {@code BillingDtos.PlanView} record; field names are kept
 * identical so the frontend contract is preserved.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingPlanDtoOut {

    @Schema(description = "Plan id")
    private UUID id;

    @Schema(description = "Plan code")
    private String code;

    @Schema(description = "Plan display name")
    private String name;

    @Schema(description = "Plan description")
    private String description;

    @Schema(description = "Monthly price in cents, in the plan's source currency (CNY, moneda de 1688)")
    private int priceMonthlyCents;

    @Schema(description = "Yearly price in cents, in the plan's source currency (CNY)")
    private int priceYearlyCents;

    @Schema(description = "Ancla de precio mensual en EUR (céntimos) para la UE; 0 = sin override")
    private int priceMonthlyEurCents;

    @Schema(description = "Ancla de precio anual en EUR (céntimos) para la UE; 0 = sin override")
    private int priceYearlyEurCents;

    @Schema(description = "ISO currency code de origen del plan (CNY)")
    private String currency;

    // Precios YA convertidos a la moneda de display del usuario (X-Currency), como los productos.
    // El frontend SOLO pinta el *Formatted; ningún cálculo de precio vive en el cliente.
    @Schema(description = "Precio mensual convertido a la moneda de display del usuario")
    private BigDecimal displayMonthly;

    @Schema(description = "Precio anual convertido a la moneda de display del usuario")
    private BigDecimal displayYearly;

    @Schema(description = "Precio mensual ya formateado (símbolo + locale) para pintar")
    private String displayMonthlyFormatted;

    @Schema(description = "Precio anual ya formateado para pintar")
    private String displayYearlyFormatted;

    @Schema(description = "Moneda de display aplicada (X-Currency)")
    private String displayCurrency;

    @Schema(description = "Ordering position")
    private int position;

    @Schema(description = "Feature limits keyed by feature key")
    private Map<String, Long> limits;
}
