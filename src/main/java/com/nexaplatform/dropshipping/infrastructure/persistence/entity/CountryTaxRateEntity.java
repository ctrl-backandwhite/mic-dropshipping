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
 * Tasa de impuesto (IVA/sales tax) por país de envío. {@code rateBps} en puntos básicos
 * (2100 = 21%). Se aplica sobre (subtotal + envío) al crear el pedido si el país tiene una tasa activa.
 */
@Entity
@Table(name = "country_tax_rate")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CountryTaxRateEntity extends BaseEntity {

    @Column(name = "country_code", nullable = false, unique = true, length = 2)
    private String countryCode;

    @Column(length = 80)
    private String label;

    @Column(name = "rate_bps", nullable = false)
    private int rateBps;

    @Column(nullable = false)
    private boolean active;
}
