package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** DROP-436: secreto TOTP por usuario + recovery codes hasheados. */
@Entity
@Table(name = "totp_secret")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TotpSecretEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "secret_enc", nullable = false, length = 255)
    private String secretEnc; // Base32 secret cifrado con TokenCryptoService (AES-GCM)

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = false;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    /** JSON array de bcrypt hashes de los 10 backup codes (used codes son null). */
    @Column(name = "recovery_codes_hash", columnDefinition = "text")
    private String recoveryCodesHash;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
