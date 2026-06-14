package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "variant_value")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VariantValueEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "option_id", nullable = false)
    private VariantOptionEntity option;

    @Column(name = "value_zh", nullable = false, length = 200)
    private String valueZh;

    @Column(length = 200)
    private String value;

    @Column(name = "image_source_url", length = 800)
    private String imageSourceUrl;

    @Column(name = "image_cdn_url", length = 800)
    private String imageCdnUrl;

    @Column(nullable = false)
    private int position;

    /** Traducciones por idioma del valor (es/en/pt/zh…). */
    @Builder.Default
    @OneToMany(mappedBy = "variantValue", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<VariantValueTranslationEntity> translations = new ArrayList<>();
}
