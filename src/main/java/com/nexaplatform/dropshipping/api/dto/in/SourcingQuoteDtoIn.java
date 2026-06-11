package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/** DROP-3: input payload to submit a quote for a sourcing request. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourcingQuoteDtoIn {

    @Positive
    @Schema(description = "Quoted price in USD cents")
    private int priceUsdCents;

    @Positive
    @Schema(description = "Estimated delivery in days")
    private int etaDays;

    @Schema(description = "Minimum order quantity")
    private Integer moq;

    @Schema(description = "Quote notes")
    private String notes;
}
