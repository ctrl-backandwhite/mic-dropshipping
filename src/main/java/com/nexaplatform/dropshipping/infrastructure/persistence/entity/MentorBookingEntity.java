package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "mentor_booking")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MentorBookingEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mentor_id", nullable = false)
    private MentorProfileEntity mentor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "learner_id", nullable = false)
    private UserEntity learner;

    @Column(name = "starts_at", nullable = false) private Instant startsAt;
    @Column(name = "duration_min", nullable = false)
    @Builder.Default private int durationMin = 60;
    @Column(nullable = false, length = 20)
    @Builder.Default private String status = "REQUESTED";
    @Column(length = 300) private String topic;
}
