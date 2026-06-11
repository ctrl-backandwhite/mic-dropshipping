package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.math.BigDecimal;

/** DROP-499: input payload for the admin quick-edit of a product. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminProductQuickEditDtoIn {

    @Schema(description = "Localized product title")
    private String title;

    @Schema(description = "Brand")
    private String brand;

    @Schema(description = "Base price in the product currency")
    private BigDecimal basePrice;

    @Schema(description = "ISO currency code")
    private String currency;

    @Schema(description = "Minimum order quantity")
    private Integer moq;

    @Schema(description = "Localized short description")
    private String shortDescription;
}
