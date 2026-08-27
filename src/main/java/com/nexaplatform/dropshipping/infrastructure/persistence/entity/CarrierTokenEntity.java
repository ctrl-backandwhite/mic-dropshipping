package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * El token vivo de la API de un transportista.
 *
 * <p>Vive en la base y no en memoria porque CJ limita la autenticación a una llamada por segundo y
 * devuelve el mismo token durante 24 horas: si cada arranque pidiera uno nuevo, dos réplicas o un
 * despliegue en caliente bastarían para chocar contra el límite, y se notaría en mitad de un checkout.
 */
@Entity
@Table(name = "carrier_token")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CarrierTokenEntity extends BaseEntity {

    /** Quién lo emitió: {@code CJ}, y mañana cualquier otro. */
    @Column(name = "carrier", nullable = false, length = 32)
    private String carrier;

    /**
     * El token que va en cada petición. En {@code TEXT} a propósito: el real de CJ ocupa 566 caracteres
     * y en un {@code VARCHAR(255)} la fila se rechaza al renovar, diez días después de desplegar.
     */
    @Column(name = "access_token", nullable = false, columnDefinition = "text")
    private String accessToken;

    /** Sirve para renovar sin volver a mandar la clave. */
    @Column(name = "refresh_token", columnDefinition = "text")
    private String refreshToken;

    /** Identificador de la cuenta. Es el secreto con el que CJ firma los avisos del webhook. */
    @Column(name = "open_id", length = 64)
    private String openId;

    /**
     * Cuándo se obtuvo. La renovación se mide contra esto y no contra la caducidad que anuncia el
     * transportista, porque se renueva mucho antes de que caduque.
     */
    @Column(name = "obtained_at", nullable = false)
    private Instant obtainedAt;

    @Column(name = "access_expires_at")
    private Instant accessExpiresAt;

    /** Si esta fecha ya pasó, no se puede refrescar: hay que autenticarse con la clave otra vez. */
    @Column(name = "refresh_expires_at")
    private Instant refreshExpiresAt;
}
