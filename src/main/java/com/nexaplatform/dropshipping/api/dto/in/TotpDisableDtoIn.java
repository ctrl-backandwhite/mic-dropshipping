package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/** Input payload to disable 2FA (requires password re-entry). */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TotpDisableDtoIn {

    @NotBlank
    @Schema(description = "The user's current account password")
    private String password;
}
