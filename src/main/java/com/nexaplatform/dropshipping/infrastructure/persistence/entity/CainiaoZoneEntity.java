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
 * Cobertura de envío de Cainiao por país (tabla {@code cainiao_shipping_zone}): fuente de verdad de a
 * qué países SE PUEDE enviar (la plataforma solo envía a países cubiertos) + tarifa por destino y
 * ventana de entrega estimada. Editable desde el admin. País ausente o {@code enabled=false} => destino
 * no válido. Distinto del {@link ShippingZoneEntity} por-proveedor (DROP-675) y del calculador DROP-13.
 */
@Entity
@Table(name = "cainiao_shipping_zone")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CainiaoZoneEntity extends BaseEntity {

    @Column(name = "country_code", nullable = false, unique = true, length = 2)
    private String countryCode;

    @Column(name = "country_name", nullable = false, length = 80)
    private String countryName;

    @Column(nullable = false, length = 30)
    private String zone;

    @Column(name = "base_cents", nullable = false)
    private int baseCents;

    @Column(name = "per_kg_cents", nullable = false)
    private int perKgCents;

    @Column(name = "eta_min_days", nullable = false)
    private int etaMinDays;

    @Column(name = "eta_max_days", nullable = false)
    private int etaMaxDays;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;
}
