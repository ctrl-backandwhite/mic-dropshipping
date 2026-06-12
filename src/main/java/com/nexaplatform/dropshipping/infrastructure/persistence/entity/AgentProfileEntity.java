package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "agent_profile")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentProfileEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UserEntity user;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;
    @Column(nullable = false, length = 20)
    private String tier;
    @Column(length = 1000)
    private String bio;
    @Column(name = "avatar_url", length = 800)
    private String avatarUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> languages = new ArrayList<>();

    @Column(name = "success_rate", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal successRate = BigDecimal.ZERO;
    @Column(name = "avg_response_hours", precision = 6, scale = 2)
    @Builder.Default
    private BigDecimal avgResponseHours = BigDecimal.ZERO;
    @Column(precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal satisfaction = BigDecimal.ZERO;
    @Column(name = "completed_jobs", nullable = false)
    @Builder.Default
    private int completedJobs = 0;
    @Column(name = "hourly_rate_usd_cents")
    private Integer hourlyRateUsdCents;
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
