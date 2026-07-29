package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Perfil aduanero y de embalaje de una categoría-hoja: lo que YunExpress necesita declarar y tarifar.
 *
 * <p>El tipo de producto ya determina la partida arancelaria, el material y el uso que se declaran, si
 * lleva batería ({@code PackageType} 0 普货 / 1 带电) y el formato de paquete del que sale el peso
 * volumétrico. Tenerlo por categoría evita rellenar miles de fichas a mano y permite corregir una
 * partida en una sola fila.
 *
 * <p>Las filas cuyo {@code categorySlug} acaba en {@code -*} son el fallback de toda la familia
 * (p. ej. {@code moda-muj-*}).
 */
@Entity
@Table(name = "category_customs_profile")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryCustomsProfileEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "category_slug", nullable = false, unique = true, length = 64)
    private String categorySlug;

    /** Partida del Sistema Armonizado a 6 dígitos (régimen H7 de la UE para envíos de bajo valor). */
    @Column(name = "hs_code", length = 12)
    private String hsCode;

    /** {@code InvoicePart} (材质): material declarado, en inglés. */
    @Column(length = 255)
    private String material;

    /** {@code InvoiceUsage} (用途): uso declarado, en inglés. */
    @Column(name = "usage_text", length = 255)
    private String usageText;

    /** NONE | BUILT_IN | WITH_EQUIPMENT. Distinto de NONE ⇒ {@code PackageType=1}. */
    @Column(name = "battery_type", length = 20)
    private String batteryType;

    @Column(name = "pack_length_mm")
    private Integer packLengthMm;

    @Column(name = "pack_width_mm")
    private Integer packWidthMm;

    @Column(name = "pack_height_mm")
    private Integer packHeightMm;
}
