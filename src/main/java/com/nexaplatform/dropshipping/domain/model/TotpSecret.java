package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.UUID;

/**
 * Nested sub-entity of the {@link User} aggregate: the per-user TOTP second
 * factor. Mirrors the {@code TotpSecretEntity}; the encrypted secret and the
 * hashed recovery codes are carried opaquely (the use/crypto logic stays in the
 * TOTP use case).
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TotpSecret {

    private UUID userId;
    private String secretEnc;
    private boolean enabled;
    private Instant lastUsedAt;
    private String recoveryCodesHash;
    private Instant createdAt;
    private Instant updatedAt;
}
