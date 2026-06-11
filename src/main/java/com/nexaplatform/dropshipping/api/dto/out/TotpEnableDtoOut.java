package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.List;

/** Output of enabling 2FA: the one-time backup codes (shown only once). */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TotpEnableDtoOut {

    @Schema(description = "One-time backup codes, shown only once")
    private List<String> backupCodes;
}
