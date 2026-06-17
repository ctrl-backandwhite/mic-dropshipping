package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Sesión/dispositivo conectado de un usuario (tabla {@code user_session}). Una fila por login,
 * identificada por el token de dispositivo (cookie {@code nx_device}). Permite listar y revocar.
 */
@Entity
@Table(name = "user_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSessionEntity {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "device_token", nullable = false, length = 120)
    private String deviceToken;

    @Column(length = 160)
    private String device;

    @Column(name = "user_agent", length = 400)
    private String userAgent;

    @Column(length = 64)
    private String ip;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "last_seen_at", nullable = false)
    @Builder.Default
    private Instant lastSeenAt = Instant.now();

    @Column(name = "revoked_at")
    private Instant revokedAt;
}
