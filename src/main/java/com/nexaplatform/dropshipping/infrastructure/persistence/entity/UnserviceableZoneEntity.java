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
 * Rango de código postal al que el transportista NO entrega, dentro de un país que sí cubre.
 *
 * <p>La cobertura de {@link CainiaoZoneEntity} es por país, y eso no basta: YunExpress excluye zonas
 * concretas —en España, Baleares, Canarias, Ceuta y Melilla— que hasta ahora se vendían y no se podían
 * despachar.
 */
@Entity
@Table(name = "carrier_unserviceable_zone")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UnserviceableZoneEntity extends BaseEntity {

    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    @Column(name = "postal_from", nullable = false, length = 16)
    private String postalFrom;

    @Column(name = "postal_to", nullable = false, length = 16)
    private String postalTo;

    @Column(name = "note", length = 120)
    private String note;
}
