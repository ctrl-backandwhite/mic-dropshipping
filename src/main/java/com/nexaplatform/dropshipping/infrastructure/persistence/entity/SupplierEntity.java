package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "supplier")
// Plan 300k: proveedores cambian poco, lectura masiva en filtros.
@org.hibernate.annotations.Cache(usage = org.hibernate.annotations.CacheConcurrencyStrategy.READ_WRITE, region = "supplier")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupplierEntity extends BaseEntity {

    @Column(name = "external_id", nullable = false, length = 100)
    private String externalId;

    @Column(nullable = false, length = 40)
    private String source;

    @Column(length = 300)
    private String name;

    @Column(name = "name_zh", length = 300)
    private String nameZh;

    @Column(length = 60)
    private String country;

    @Column(length = 120)
    private String city;

    @Column(precision = 3, scale = 2)
    private BigDecimal rating;

    @Column(name = "years_active")
    private Integer yearsActive;

    @Column(nullable = false)
    private boolean verified;

    @Column(name = "trust_pass", nullable = false)
    private boolean trustPass;

    @Column(name = "profile_url", length = 500)
    private String profileUrl;
}
