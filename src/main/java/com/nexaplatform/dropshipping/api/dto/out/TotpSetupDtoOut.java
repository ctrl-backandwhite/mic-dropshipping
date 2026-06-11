package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/** Output of starting the 2FA setup: the secret and the otpauth URL for the QR. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TotpSetupDtoOut {

    @Schema(description = "Base32-encoded shared secret")
    private String base32Secret;

    @Schema(description = "otpauth:// URL to render as a QR code")
    private String otpauthUrl;
}
