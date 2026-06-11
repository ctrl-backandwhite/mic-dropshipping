package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "academy_enrollment",
       uniqueConstraints = @UniqueConstraint(columnNames = { "user_id", "course_id" }))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AcademyEnrollmentEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private AcademyCourseEntity course;

    @Column(name = "progress_pct", nullable = false, precision = 5, scale = 2)
    @Builder.Default private BigDecimal progressPct = BigDecimal.ZERO;

    @Column(name = "completed_at") private Instant completedAt;
}
