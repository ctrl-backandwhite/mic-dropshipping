package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "sourcing_quote")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SourcingQuoteEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private SourcingRequestEntity request;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id")
    private AgentProfileEntity agent;

    @Column(name = "price_usd_cents", nullable = false) private int priceUsdCents;
    @Column(name = "eta_days", nullable = false) private int etaDays;
    @Column private Integer moq;
    @Column(length = 2000) private String notes;
    @Column(nullable = false, length = 20)
    @Builder.Default private String status = "OPEN";
}
