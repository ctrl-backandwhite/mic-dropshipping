package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/** Input payload to activate an account with an activation code. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivateDtoIn {

    @NotBlank
    @Size(max = 64)
    @Schema(description = "Activation code from the welcome email")
    private String code;
}
