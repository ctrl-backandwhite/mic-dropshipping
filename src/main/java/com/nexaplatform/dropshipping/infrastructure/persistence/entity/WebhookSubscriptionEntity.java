package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Partner-configurable HTTPS endpoint that NX036 calls with HMAC-SHA256-signed payloads
 * whenever a subscribed event happens (order.created, order.shipped, …).
 */
@Entity
@Table(name = "webhook_subscription")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookSubscriptionEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UserEntity user;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "target_url", nullable = false, length = 800)
    private String targetUrl;

    @Column(nullable = false, length = 120)
    private String secret;

    /** Event type filter, e.g. ["order.created","order.shipped"]; empty list = subscribe to all. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> events = new ArrayList<>();

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(length = 400)
    private String description;
}
