package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/** Output of the 2FA status check for the current user. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TotpStatusDtoOut {

    @Schema(description = "Whether 2FA is currently enabled for the user", example = "true")
    private boolean enabled;
}
