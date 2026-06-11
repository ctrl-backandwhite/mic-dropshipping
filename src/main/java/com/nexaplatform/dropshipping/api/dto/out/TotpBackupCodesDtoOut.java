package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.List;

/** Output of regenerating the 2FA backup codes. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TotpBackupCodesDtoOut {

    @Schema(description = "Freshly generated one-time backup codes")
    private List<String> backupCodes;
}
