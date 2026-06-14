package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Idioma de la tienda configurable por el operador (ilimitado). */
@Entity
@Table(name = "store_language")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoreLanguageEntity extends BaseEntity {

    @Column(nullable = false, length = 8)
    private String code;

    @Column(nullable = false, length = 80)
    private String label;

    @Column(length = 16)
    private String flag;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;
}
