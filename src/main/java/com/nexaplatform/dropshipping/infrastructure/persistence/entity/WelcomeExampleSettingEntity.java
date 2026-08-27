package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Ajuste (fila única, id = 1) con los tres productos que ilustran la guía de bienvenida.
 *
 * <p>A {@code null} manda la elección automática, que es lo normal: hacen falta dos productos que
 * compartan partida arancelaria y uno de otra distinta, o la lección —el arancel se paga por partida, no
 * por unidad— no se ve al tocar el simulador. Estas columnas existen para que el admin pueda destacar
 * otros productos sin tocar código.
 */
@Entity
@Table(name = "welcome_example_setting")
@Getter
@Setter
@NoArgsConstructor
public class WelcomeExampleSettingEntity {

    @Id
    @Column(nullable = false)
    private Short id;

    @Column(name = "product_id_1")
    private UUID productId1;

    @Column(name = "product_id_2")
    private UUID productId2;

    @Column(name = "product_id_3")
    private UUID productId3;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", length = 120)
    private String updatedBy;
}
