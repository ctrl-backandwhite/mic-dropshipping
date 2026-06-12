package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "support_ticket")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupportTicketEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(nullable = false, length = 20)
    private String kind; // SUPPORT | DISPUTE
    @Column(nullable = false, length = 300)
    private String subject;
    @Column(columnDefinition = "TEXT")
    private String body;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private CustomerOrderEntity order;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "OPEN";
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String priority = "NORMAL";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to")
    private UserEntity assignedTo;

    @Column(columnDefinition = "TEXT")
    private String resolution;
}
