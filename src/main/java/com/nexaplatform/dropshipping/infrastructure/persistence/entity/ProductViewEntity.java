package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Visita de un usuario a la ficha de un producto. Hay UNA fila por (usuario, producto): volver a abrir la
 * misma ficha no inserta otra, actualiza {@code viewedAt} y suma un punto a {@code viewCount}. Así el
 * historial no se llena del mismo producto repetido por recargar la página.
 */
@Entity
@Table(name = "product_view", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "product_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductViewEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "product_id", nullable = false, columnDefinition = "uuid")
    private UUID productId;

    /** Momento de la ÚLTIMA visita: es lo que ordena el historial y lo que decide si entra en el correo. */
    @Column(name = "viewed_at", nullable = false)
    private Instant viewedAt;

    /** Cuántas veces ha vuelto a la ficha. Es lo único que se perdería al consolidar las visitas. */
    @Column(name = "view_count", nullable = false)
    @Builder.Default
    private int viewCount = 1;

    /*
     * El precio TAL COMO LO VIO esa persona, ya con todos los cálculos hechos.
     *
     * <p>Se guarda para no tener que rehacerlos al pintar el historial: por cada una de las cincuenta
     * fichas habría que convertir la divisa, aplicar el margen del país de registro, el IVA, el envío, las
     * dos bolsas de subvención y el recargo fijo. Eso es lo que hacía lenta la página.
     *
     * <p>Lo calcula el servidor al anotar la visita. No llega del navegador: un importe que viajara desde
     * el cliente lo podría poner cualquiera.
     *
     * <p>Es el precio de ese momento, no el de hoy. Sirve para que la persona reconozca lo que estuvo
     * mirando; lo que se cobra se recalcula en la cesta, como siempre.
     */
    @Column(name = "precio_visto", precision = 18, scale = 4)
    private BigDecimal precioVisto;

    @Column(name = "moneda_vista", length = 3)
    private String monedaVista;

    /** Ya formateado ("28,26 €"): el formato depende del idioma y la moneda de quien miraba. */
    @Column(name = "precio_visto_formateado", length = 40)
    private String precioVistoFormateado;
}
