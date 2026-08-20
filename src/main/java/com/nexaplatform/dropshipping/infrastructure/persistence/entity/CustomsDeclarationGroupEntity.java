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

import java.time.Instant;
import java.util.UUID;

/**
 * La descripción genérica con la que se declara una terna de mercancía (tabla
 * {@code customs_declaration_group}).
 *
 * <p>Una fila por <b>partida + material + uso</b>. Todos los productos que comparten esa terna viajan
 * con la misma descripción en la declaración, así que la aduana los cuenta como <b>una sola línea</b> y
 * se paga el derecho de 3 EUR una vez, no una por producto. Es el ejemplo oficial de la Comisión:
 * anorak, cortavientos y cazadora bajo la subpartida 6104 19 pagan 3 EUR, no 9.
 *
 * <p><b>{@link #approvedAt} no es informativo.</b> Mientras esté a {@code null} el grupo <b>no
 * agrupa</b>: cada producto sigue siendo su propia línea de declaración. Es la salvaguarda que hace que
 * cargar productos nuevos no pueda abaratar el arancel por accidente — se cobra de más en el peor caso,
 * nunca de menos. Y es una firma: lo que se declara ante 27 aduanas responde de ello el declarante, así
 * que queda constancia de quién aprobó cada texto.
 *
 * <p>La terna se guarda <b>normalizada</b> (recortada, sin espacios dobles, en mayúsculas) para que
 * «Cotton» y «cotton  » sean el mismo grupo. Quien escriba aquí debe normalizar con
 * {@code CustomsDeclarationGroupService.normalizeKeyPart}.
 */
@Entity
@Table(name = "customs_declaration_group")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomsDeclarationGroupEntity {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    /** Subpartida del Sistema Armonizado a 6 dígitos: el nivel que se declara en el H7. */
    @Column(name = "hs6", nullable = false, length = 6)
    private String hs6;

    @Column(name = "material", nullable = false, length = 120)
    private String material;

    @Column(name = "usage_code", nullable = false, length = 120)
    private String usageCode;

    /** Descripción genérica en inglés: el {@code EName} que se le transmite al transportista. */
    @Column(name = "ename", nullable = false, length = 512)
    private String ename;

    @Column(name = "cname", nullable = false, length = 512)
    private String cname;

    /** Cuántos productos del catálogo caen en esta terna. Informativo, para poder empezar por las grandes. */
    @Column(name = "product_count", nullable = false)
    private Integer productCount;

    /** Sin esto el grupo NO agrupa. Ver el javadoc de la clase. */
    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approved_by", length = 120)
    private String approvedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
