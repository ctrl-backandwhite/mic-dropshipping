package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/** A newsletter subscriber (DROP email/newsletter feature). */
@Entity
@Table(name = "newsletter_subscriber")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewsletterSubscriberEntity extends BaseEntity {

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "user_id", columnDefinition = "uuid")
    private UUID userId;

    /** SUBSCRIBED or UNSUBSCRIBED. */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "SUBSCRIBED";

    @Column(nullable = false, length = 80)
    private String token;

    @Column(length = 40)
    private String source;

    /** Cuándo se confirmó el alta desde el propio buzón. NULL = pendiente o anterior al doble opt-in. */
    @Column(name = "confirmed_at")
    private java.time.Instant confirmedAt;
}
