package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "sourcing_request")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourcingRequestEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UserEntity user;

    @Column(length = 40)
    private String source;
    @Column(name = "external_id", length = 120)
    private String externalId;
    @Column(name = "source_url", nullable = false, length = 800)
    private String sourceUrl;
    @Column(name = "title_hint", length = 400)
    private String titleHint;
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";
    @Column(name = "plan_quota", length = 20)
    private String planQuota;
    @Column(length = 2000)
    private String notes;
    @Column(name = "selected_quote_id")
    private UUID selectedQuoteId;
}
