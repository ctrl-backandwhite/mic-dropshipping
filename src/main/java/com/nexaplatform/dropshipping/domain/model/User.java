package com.nexaplatform.dropshipping.domain.model;

import com.nexaplatform.dropshipping.domain.enums.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Pure domain model for the Auth/User aggregate root. Use cases operate on this
 * model; mappers translate to/from DtoIn/DtoOut (api) and the JPA entity (infra).
 *
 * <p>Carries the auth-side state (activation, lockout, last login) plus the
 * profile fields. The nested sub-entities of the aggregate are carried as nested
 * model objects/lists: the optional {@link TotpSecret} second factor and the
 * outstanding {@link PasswordResetToken} list. The {@code totpEnabled} flag is a
 * computed read-only field filled by the use case (2FA status) and has no direct
 * column on the user table.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    private UUID id;

    private String email;
    private String passwordHash;
    private UserRole role;
    private boolean active;

    private String activationCode;
    private Instant activationCodeExpiresAt;

    private int failedLoginCount;
    private Instant lockedUntil;
    private Instant lastLogin;

    private String displayName;
    /** Nombre de pila. */
    private String firstName;
    /** Primer apellido. */
    private String lastName1;
    /** Segundo apellido (opcional). */
    private String lastName2;
    private String companyName;
    private String country;
    private String phone;
    private String avatarUrl;
    private String language;

    /** Whether this account has a confirmed link to a Google identity (social login). */
    private boolean googleLinked;

    /** Whether this account has already consumed its one-time FREE trial (1-month free plan). */
    private boolean freeTrialUsed;

    /** Nested second-factor data of the aggregate (null when 2FA was never set up). */
    private TotpSecret totp;

    /** Nested outstanding password-reset tokens issued for this user. */
    private List<PasswordResetToken> resetTokens;

    /** Computed read field: whether 2FA is currently enabled. Filled by the use case. */
    private boolean totpEnabled;

    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
