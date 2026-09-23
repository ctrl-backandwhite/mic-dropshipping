package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Traducción por idioma de un valor de variación (p.ej. el color en es/en/pt/zh). */
@Entity
@Table(name = "variant_value_translation", uniqueConstraints = @UniqueConstraint(columnNames = {"variant_value_id",
        "language"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VariantValueTranslationEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_value_id", nullable = false)
    private VariantValueEntity variantValue;

    @Column(nullable = false, length = 8)
    private String language;

    @Column(nullable = false, length = 200)
    private String value;
}
