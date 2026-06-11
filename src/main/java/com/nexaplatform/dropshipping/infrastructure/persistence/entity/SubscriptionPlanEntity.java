package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "subscription_plan")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionPlanEntity extends BaseEntity {

    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(name = "price_monthly_cents")
    private int priceMonthlyCents;

    @Column(name = "price_yearly_cents")
    private int priceYearlyCents;

    @Column(length = 8)
    private String currency;

    @Column(name = "stripe_monthly_price_id", length = 120)
    private String stripeMonthlyPriceId;

    @Column(name = "stripe_yearly_price_id", length = 120)
    private String stripeYearlyPriceId;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private int position;

    @Builder.Default
    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<PlanFeatureEntity> features = new ArrayList<>();
}
