package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "odm_project")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OdmProjectEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(nullable = false, length = 20)
    private String kind; // ODM_FREE | ODM_PAID | OEM | CUSTOM_PACKAGING
    @Column(nullable = false, length = 200)
    private String title;
    @Column(columnDefinition = "TEXT")
    private String brief;
    @Column(name = "budget_usd_cents")
    private Integer budgetUsdCents;
    @Column(name = "sla_days")
    private Integer slaDays;
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "INTAKE";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to")
    private UserEntity assignedTo;
}
