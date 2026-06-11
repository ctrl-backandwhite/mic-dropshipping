package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "wallet")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WalletEntity extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private UserEntity user;

    @Column(name = "balance_usd_cents", nullable = false)
    private long balanceUsdCents;

    @Column(name = "hold_usd_cents", nullable = false)
    private long holdUsdCents;

    @Column(name = "currency_default", length = 8)
    private String currencyDefault;

    @Column(nullable = false, length = 20)
    private String status;  // ACTIVE | FROZEN
}
