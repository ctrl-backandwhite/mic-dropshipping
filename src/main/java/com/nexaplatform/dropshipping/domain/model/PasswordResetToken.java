package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.UUID;

/**
 * Nested sub-entity of the {@link User} aggregate: a password-reset token. The
 * {@code userId} flattens the owning-user relation the JPA entity carries as a
 * {@code @ManyToOne}.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PasswordResetToken {

    private UUID id;
    private UUID userId;
    private String tokenHash;
    private Instant expiresAt;
    private Instant consumedAt;
    private Instant createdAt;
}
