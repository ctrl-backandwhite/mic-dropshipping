package com.nexaplatform.dropshipping.api.dto.in;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Create payload for an ODM/OEM project.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OdmProjectCreateDtoIn {

    @NotBlank
    private String kind;

    @NotBlank
    private String title;

    private String brief;

    private Integer budgetUsdCents;
}
