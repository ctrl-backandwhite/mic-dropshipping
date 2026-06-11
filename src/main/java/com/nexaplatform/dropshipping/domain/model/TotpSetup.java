package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/**
 * Result of starting a 2FA setup: the Base32 secret and the {@code otpauth://}
 * URL the client renders as a QR. Ephemeral (never persisted) domain value
 * carried out of the TOTP use case.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TotpSetup {

    private String base32Secret;
    private String otpauthUrl;
}
