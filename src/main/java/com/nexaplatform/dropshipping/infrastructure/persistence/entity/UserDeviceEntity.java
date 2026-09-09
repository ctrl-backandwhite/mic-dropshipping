package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Dispositivo registrado para recibir avisos del sistema operativo. */
@Entity
@Table(name = "user_device")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDeviceEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(name = "push_token", nullable = false, length = 255, unique = true)
    private String pushToken;

    @Column(name = "plataforma", nullable = false, length = 16)
    @Builder.Default
    private String plataforma = "UNKNOWN";

    @Column(name = "creado_el", nullable = false)
    private Instant creadoEl;

    @Column(name = "ultima_senal", nullable = false)
    private Instant ultimaSenal;
}
