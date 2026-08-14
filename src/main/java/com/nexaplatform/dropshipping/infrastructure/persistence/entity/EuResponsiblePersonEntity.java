package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Operador económico establecido en la Unión (fila única, id = 1).
 *
 * <p>El art. 16.1 del Reglamento (UE) 2023/988 impide introducir en el mercado un producto de consumo si no
 * existe uno, y el art. 16.3 obliga a que su nombre y sus datos de contacto —dirección postal <em>y</em>
 * correo electrónico— figuren en el producto, su envase, el paquete o un documento de acompañamiento. Al no
 * controlarse el embalaje del proveedor, aquí se publica en la ficha (art. 19.b) y en la factura.
 *
 * <p>Es un ajuste del sistema y no una lista porque solo puede haber un responsable por mercado: mismo
 * patrón que {@link MoqMarginSettingEntity}.
 */
@Entity
@Table(name = "eu_responsible_person")
@Getter
@Setter
@NoArgsConstructor
public class EuResponsiblePersonEntity {

    @Id
    @Column(nullable = false)
    private Short id;

    /**
     * Solo cuando está a true se publica el bloque. Sirve de interruptor para no enseñar una dirección a
     * medias mientras se completan los datos: publicar datos de contacto incompletos no cumple el art. 16.3.
     */
    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "address_line", nullable = false, length = 300)
    private String addressLine;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    @Column(nullable = false, length = 120)
    private String city;

    @Column(length = 120)
    private String region;

    /** ISO 3166-1 alfa-2. Debe ser un país de la UE: el operador ha de estar establecido en la Unión. */
    @Column(nullable = false, length = 2)
    private String country;

    @Column(nullable = false, length = 200)
    private String email;

    @Column(length = 40)
    private String phone;

    /** Figura del art. 4.2 del Reglamento (UE) 2019/1020. Ver {@code EuOperatorRole}. */
    @Column(nullable = false, length = 40)
    private String role;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", length = 120)
    private String updatedBy;
}
