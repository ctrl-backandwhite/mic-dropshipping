package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import com.nexaplatform.dropshipping.domain.enums.UserRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserEntity extends BaseEntity {

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private UserRole role;

    @Column(nullable = false)
    private boolean active;

    /** Email preference: when true, the user is excluded from marketing/affiliate/newsletter emails. */
    @Column(name = "marketing_opt_out", nullable = false)
    @Builder.Default
    private boolean marketingOptOut = false;

    /**
     * Whether this account has already consumed its one-time FREE trial (1-month free plan). Set to true
     * the first time the user subscribes to the free plan; a second free subscription is then rejected.
     */
    @Column(name = "free_trial_used", nullable = false)
    @Builder.Default
    private boolean freeTrialUsed = false;

    @Column(name = "activation_code", length = 64)
    private String activationCode;

    @Column(name = "activation_code_expires_at")
    private Instant activationCodeExpiresAt;

    /** Marca de BORRADO LÓGICO (soft delete): cuando no es null la cuenta está dada de baja. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** Código temporal para confirmar la eliminación de la cuenta (enviado por email). */
    @Column(name = "deletion_code", length = 64)
    private String deletionCode;

    /** Instante en que caduca {@link #deletionCode}. */
    @Column(name = "deletion_code_expires_at")
    private Instant deletionCodeExpiresAt;

    @Column(name = "failed_login_count")
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login")
    private Instant lastLogin;

    @Column(name = "display_name", length = 120)
    private String displayName;

    @Column(name = "first_name", length = 80)
    private String firstName;

    @Column(name = "last_name_1", length = 80)
    private String lastName1;

    @Column(name = "last_name_2", length = 80)
    private String lastName2;

    @Column(name = "company_name", length = 180)
    private String companyName;

    /** Customer de Stripe del usuario (para tarjetas guardadas + suscripciones recurrentes). */
    @Column(name = "stripe_customer_id", length = 64)
    private String stripeCustomerId;

    @Column(length = 60)
    private String country;

    @Column(length = 40)
    private String phone;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(length = 8)
    private String language;

    @Column(name = "google_linked", nullable = false)
    private boolean googleLinked;
}
