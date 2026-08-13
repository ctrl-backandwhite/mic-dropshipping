package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Cuenta PayPal que el usuario guarda como método de pago para el checkout (one-off). Las TARJETAS viven
 * en Stripe; esto solo cubre PayPal. El correo va CIFRADO ({@code paypalEmailEnc}, AES-256-GCM vía
 * TokenCryptoService): nadie que lea la BD puede obtenerlo. El "predeterminado" es un puntero unificado
 * en {@code users.default_payment_ref} ('paypal:&lt;id&gt;' o el pm_ de Stripe), no una columna aquí.
 */
@Entity
@Table(name = "payment_method_paypal")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayPalPaymentMethodEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Correo de PayPal CIFRADO (prefijo {@code gcm:}). Se descifra solo en memoria para mostrar el máscara. */
    @Column(name = "paypal_email_enc")
    private String paypalEmailEnc;

    @Column(name = "vault_id")
    private String vaultId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
