package com.nexaplatform.dropshipping.api.dto.in;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Partial-update payload for a subscription plan. All fields are optional;
 * null fields are ignored by the MapStruct update mapper (no overwrite).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminPlanUpdateDtoIn {

    @Size(max = 120)
    private String name;

    @Size(max = 1000)
    private String description;

    @PositiveOrZero
    private Integer priceMonthlyCents;

    @PositiveOrZero
    private Integer priceYearlyCents;

    private Boolean active;
}
