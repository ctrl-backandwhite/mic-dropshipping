package com.nexaplatform.dropshipping.api.dto.in;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Create/update payload for a supplier (admin manual management). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminSupplierUpsertDtoIn {

    @NotBlank
    private String name;

    private String nameZh;

    private String country;

    private String city;

    private BigDecimal rating;

    private Integer yearsActive;

    private Boolean verified;

    private Boolean trustPass;

    private String profileUrl;
}
