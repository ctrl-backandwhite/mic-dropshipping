package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Regla de despacho aduanero por país de destino: modo fiscal (DDP/DDU), umbral de minimis del régimen
 * simplificado (IOSS en la UE, VOEC en Noruega, Low Value Goods en AU/NZ...) y los recargos que el
 * transportista cobra por gestionar el despacho.
 *
 * <p>El umbral se guarda en su <b>divisa legal</b> ({@code deMinimisCurrency}) porque cada país lo fija en
 * la suya (150 EUR en la UE, 135 GBP en UK, 3.000 NOK en Noruega); el servicio lo convierte con la tasa del
 * día. {@code deMinimisAmount = 0} significa "sin franquicia": todo envío declara y paga.
 *
 * @see com.nexaplatform.dropshipping.application.service.CustomsValuationService
 */
@Entity
@Table(name = "country_customs_rule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CountryCustomsRuleEntity extends BaseEntity {

    @Column(name = "country_code", nullable = false, unique = true, length = 2)
    private String countryCode;

    /** DDP = el comercio paga los impuestos vía transportista; DDU = los paga el destinatario en destino. */
    @Column(name = "tax_mode", nullable = false, length = 3)
    private String taxMode;

    /** Umbral del régimen simplificado, en {@link #deMinimisCurrency}. 0 = sin franquicia. */
    @Column(name = "de_minimis_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal deMinimisAmount;

    @Column(name = "de_minimis_currency", nullable = false, length = 3)
    private String deMinimisCurrency;

    /** Qué hacer al superar el umbral: {@code SURCHARGE}, {@code ALLOW} o {@code BLOCK}. */
    @Column(name = "over_threshold_policy", nullable = false, length = 10)
    private String overThresholdPolicy;

    /** Recargo fijo por paquete del despacho DDP (céntimos USD). */
    @Column(name = "handling_fee_cents", nullable = false)
    private int handlingFeeCents;

    /** Parte variable del recargo DDP en puntos básicos sobre el impuesto liquidado (250 = 2,5%). */
    @Column(name = "handling_percent_bps", nullable = false)
    private int handlingPercentBps;

    /** Recargo fijo adicional por despacho formal cuando se supera el umbral (céntimos USD). */
    @Column(name = "over_threshold_surcharge_cents", nullable = false)
    private int overThresholdSurchargeCents;

    /** Arancel estimado sobre el valor intrínseco cuando se supera el umbral (puntos básicos). */
    @Column(name = "duty_rate_bps", nullable = false)
    private int dutyRateBps;

    @Column(nullable = false)
    private boolean active;
}
