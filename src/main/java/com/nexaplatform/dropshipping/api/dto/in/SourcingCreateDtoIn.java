package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/** DROP-3: input payload to create a sourcing request. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourcingCreateDtoIn {

    @NotBlank
    @Schema(description = "Source URL to source from")
    private String url;

    @Schema(description = "Optional title hint")
    private String titleHint;

    @Schema(description = "Free-form notes")
    private String notes;
}
