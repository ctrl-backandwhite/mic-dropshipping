package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "mentor_profile",
       uniqueConstraints = @UniqueConstraint(columnNames = { "user_id" }))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MentorProfileEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(nullable = false, length = 200) private String headline;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default private List<String> expertise = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default private List<String> languages = new ArrayList<>();

    @Column(name = "hourly_rate_usd_cents", nullable = false)
    @Builder.Default private int hourlyRateUsdCents = 0;
    @Column(columnDefinition = "TEXT") private String bio;
    @Column(length = 40) private String timezone;
    @Column(nullable = false)
    @Builder.Default private boolean active = true;
}
