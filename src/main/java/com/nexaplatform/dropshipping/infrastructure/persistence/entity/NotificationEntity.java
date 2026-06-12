package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "notification")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;
    @Column(nullable = false, length = 300)
    private String title;
    @Column(columnDefinition = "TEXT")
    private String body;
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String channel = "IN_APP";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> payload = new HashMap<>();

    @Column(name = "read_at")
    private Instant readAt;
}
