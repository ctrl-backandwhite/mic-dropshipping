package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

/** Audit record of a sent newsletter campaign. */
@Entity
@Table(name = "newsletter_campaign")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewsletterCampaignEntity extends BaseEntity {

    @Column(nullable = false, length = 300)
    private String subject;

    @Column(name = "body_html", nullable = false, columnDefinition = "text")
    private String bodyHtml;

    @Column(nullable = false)
    @Builder.Default
    private int recipients = 0;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "SENT";
}
