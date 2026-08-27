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
 * Límites físicos de UN canal del transportista en UN país de destino.
 *
 * <p>El peso máximo por bulto no es una constante de la cuenta: la ficha de cada línea (sección
 * 六、重量要求 de la cotización) publica un tope general y una lista de excepciones por país. La línea de
 * ropa admite 30 kg a España y 15 kg a Dinamarca; la de carga general baja a 2 kg en una docena de
 * destinos africanos y latinoamericanos. Y el peso volumétrico no se aplica igual en todas: la de ropa
 * no lo aplica («包裹实际重量不计材积») y la de carga general divide entre 8000, no entre los 6000
 * habituales del aéreo.
 *
 * <p>El país {@code *} es el valor por defecto del canal: se usa cuando el destino no aparece en la
 * lista de excepciones.
 *
 * @see com.nexaplatform.dropshipping.application.service.CarrierChannelLimitService
 */
@Entity
@Table(name = "carrier_channel_limit")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CarrierChannelLimitEntity extends BaseEntity {

    /** Código del producto logístico: {@code FZZXR} (ropa), {@code THPHR} (carga general)... */
    @Column(name = "channel_code", nullable = false, length = 32)
    private String channelCode;

    /** ISO-2 del destino, o {@code *} para el valor por defecto del canal. */
    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    /** Peso máximo por bulto en gramos. 0 = sin límite conocido (no se reparte por peso). */
    @Column(name = "max_weight_grams", nullable = false)
    private int maxWeightGrams;

    /** Divisor del peso volumétrico ({@code L×W×H cm / divisor}). <b>0 = el canal NO lo aplica.</b> */
    @Column(name = "volumetric_divisor", nullable = false)
    private int volumetricDivisor;

    /** Peso mínimo facturable en gramos: por debajo, el transportista cobra igual. 0 = sin mínimo. */
    @Column(name = "min_billable_grams", nullable = false)
    private int minBillableGrams;

    /** Medida máxima del bulto en milímetros. 0 = sin límite conocido. */
    @Column(name = "max_length_mm", nullable = false)
    private int maxLengthMm;

    @Column(name = "max_width_mm", nullable = false)
    private int maxWidthMm;

    @Column(name = "max_height_mm", nullable = false)
    private int maxHeightMm;

    /**
     * {@code true} = un bulto por envío («一票一件，不接收一票多件包裹»): si el pedido se parte, cada
     * bulto viaja como envío independiente con su propia guía.
     */
    @Column(name = "single_parcel_only", nullable = false)
    private boolean singleParcelOnly;

    /** De dónde sale el dato (hoja y sección de la cotización), para poder auditarlo. */
    @Column(name = "notes", length = 200)
    private String notes;

    @Column(nullable = false)
    private boolean active;
}
