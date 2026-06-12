package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.domain.enums.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "payment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id")
    private WalletEntity wallet;

    // Cuando purpose=ORDER_PAYMENT, esta columna referencia el customer_order pagado.
    // Si es WALLET_RECHARGE, queda null (recarga genérica de wallet).
    @Column(name = "order_id")
    private java.util.UUID orderId;

    @Column(name = "purpose", length = 20, nullable = false)
    @Builder.Default
    private String purpose = "WALLET_RECHARGE";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentStatus status;

    @Column(name = "amount_display", precision = 14, scale = 4)
    private BigDecimal amountDisplay;

    @Column(name = "currency_display", length = 8)
    private String currencyDisplay;

    @Column(name = "amount_usd_cents", nullable = false)
    private long amountUsdCents;

    @Column(name = "settlement_currency", nullable = false, length = 10)
    private String settlementCurrency;

    @Column(name = "settlement_amount", precision = 14, scale = 4)
    private BigDecimal settlementAmount;

    @Column(length = 40)
    private String provider; // stripe | paypal | coinbase | manual

    @Column(name = "provider_ref", length = 255)
    private String providerRef;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "provider_response", columnDefinition = "jsonb")
    private Map<String, Object> providerResponse;

    @Column(name = "idempotency_key", length = 128, unique = true)
    private String idempotencyKey;

    @Column(name = "crypto_address", length = 255)
    private String cryptoAddress;

    @Column(name = "crypto_chain", length = 20)
    private String cryptoChain;

    @Column(name = "crypto_expires_at")
    private Instant cryptoExpiresAt;

    @Column(name = "qr_url", length = 800)
    private String qrUrl;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;
}
