package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.application.service.TotpService;
import com.nexaplatform.dropshipping.application.usecase.TotpUseCase;
import com.nexaplatform.dropshipping.domain.model.TotpSetup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Default implementation of {@link TotpUseCase}. Orchestrates the 2FA flows and
 * returns domain values; the RFC-6238 crypto, secret encryption and recovery
 * code hashing/persistence remain in the {@code TotpService} collaborator.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TotpUseCaseImpl implements TotpUseCase {

    private final TotpService totpService;

    @Override
    public TotpSetup setup(UUID userId) {
        TotpService.SetupResult result = totpService.setup(userId);
        return TotpSetup.builder()
                .base32Secret(result.base32Secret())
                .otpauthUrl(result.otpauthUrl())
                .build();
    }

    @Override
    public List<String> verifyAndEnable(UUID userId, String otp) {
        return totpService.verifyAndEnable(userId, otp).backupCodes();
    }

    @Override
    public void disableWithPassword(UUID userId, String rawPassword) {
        totpService.disableWithPassword(userId, rawPassword);
    }

    @Override
    public List<String> regenerateBackupCodes(UUID userId) {
        return totpService.regenerateBackupCodes(userId);
    }

    @Override
    public boolean isEnabled(UUID userId) {
        return totpService.isEnabled(userId);
    }
}
