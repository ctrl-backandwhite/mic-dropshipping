package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Región (estado/provincia) de un país. Alimenta el dropdown del checkout y, donde el impuesto varía por
 * región (US/CA/BR), lleva su propia tasa {@code rateBps}. Si {@code rateBps} es NULL, se usa la tasa
 * nacional de {@code country_tax_rate}.
 */
@Entity
@Table(name = "country_region")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CountryRegionEntity extends BaseEntity {

    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    @Column(name = "region_code", nullable = false, length = 10)
    private String regionCode;

    @Column(name = "region_name", nullable = false, length = 120)
    private String regionName;

    /** Tasa de impuesto de la región en bps; NULL = usar la tasa nacional del país. */
    @Column(name = "rate_bps")
    private Integer rateBps;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private int position;
}
