package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.domain.model.TotpSetup;

import java.util.List;
import java.util.UUID;

/**
 * Use-case port for the TOTP second factor of the {@link com.nexaplatform.dropshipping.domain.model.User}
 * aggregate. Operates on/returns domain values; the controller's
 * {@code TotpDtoMapper} converts to the transport DtoOuts. Wraps the crypto and
 * recovery-code logic kept in the {@code TotpService} collaborator.
 */
public interface TotpUseCase {

    /** Start 2FA setup: returns the secret + otpauth URL (does not enable yet). */
    TotpSetup setup(UUID userId);

    /** Verify the OTP, enable 2FA and return the freshly generated backup codes. */
    List<String> verifyAndEnable(UUID userId, String otp);

    /** Disable 2FA after re-checking the user's account password. */
    void disableWithPassword(UUID userId, String rawPassword);

    /** Regenerate the backup codes (previous become invalid) and return them. */
    List<String> regenerateBackupCodes(UUID userId);

    /** Whether 2FA is currently enabled for the user. */
    boolean isEnabled(UUID userId);
}
