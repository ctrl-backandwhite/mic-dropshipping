package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Audit log of every webhook attempt. Used by the dispatcher to retry failures with
 * exponential backoff, and by the admin UI to surface delivery health per subscription.
 */
@Entity
@Table(name = "webhook_delivery")
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WebhookDeliveryEntity {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subscription_id", nullable = false)
    private WebhookSubscriptionEntity subscription;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @Column(name = "event_id", nullable = false, length = 120)
    private String eventId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(nullable = false, length = 200)
    private String signature;

    @Column(name = "target_url", nullable = false, length = 800)
    private String targetUrl;

    /** PENDING | SUCCESS | FAILED | RETRY */
    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;
}
