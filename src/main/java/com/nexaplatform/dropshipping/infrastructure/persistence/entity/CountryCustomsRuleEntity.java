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

    /**
     * Arancel estimado sobre el valor intrínseco cuando se supera el umbral (puntos básicos).
     *
     * <p><b>Solo surte efecto si se cumplen dos condiciones a la vez</b>, y hoy no se cumplen en ningún
     * país: que el pedido supere la franquicia del destino y que la política sea {@code SURCHARGE}. Los 52
     * países con franquicia real están en {@code BLOCK} (por encima no se vende) y los 34 de
     * {@code SURCHARGE} tienen la franquicia a 0, que el cálculo lee como «no configurada». Así que
     * teclear aquí un porcentaje desde el panel de administración <b>no cambia lo que se cobra</b> hasta
     * que se arregle eso. Está a 0 en los 86 países (19-ago-2026). El por qué completo, en el javadoc de
     * {@code CustomsValuationService}.
     */
    @Column(name = "duty_rate_bps", nullable = false)
    private int dutyRateBps;

    /**
     * Comisión del prepago de IVA del transportista sobre el VALOR DECLARADO, en puntos básicos
     * (200 = 2%). Solo aplica cuando el vendedor no tiene IOSS y el carrier adelanta el IVA. 0 = sin
     * comisión (p. ej. si se registra un IOSS).
     */
    @Column(name = "vat_prepay_percent_bps", nullable = false)
    private int vatPrepayPercentBps;

    /** Arancel por ARTÍCULO (producto distinto) en su divisa legal; 3 EUR en la UE. 0 = no aplica. */
    @Column(name = "per_article_fee_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal perArticleFeeAmount;

    /** Divisa del arancel por artículo (se convierte a USD con la tasa del día, como el umbral). */
    @Column(name = "per_article_fee_currency", nullable = false, length = 3)
    private String perArticleFeeCurrency;

    /**
     * Valor a partir del cual —inclusive— el TRANSPORTISTA no acepta el envío. No es el umbral fiscal:
     * el régimen de bajo valor aplica cuando el valor «no excede» la franquicia, mientras que el
     * transportista rechaza «igual o mayor». Un pedido de 150,00 EUR exactos está dentro del régimen y
     * fuera de lo que YunExpress transporta. 0 = sin límite.
     */
    @Column(name = "carrier_max_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal carrierMaxAmount;

    @Column(name = "carrier_max_currency", nullable = false, length = 3)
    private String carrierMaxCurrency;

    /** Segundo tope simultáneo en otra divisa (el contrato impone 150 EUR Y 155 USD); gana el menor. */
    @Column(name = "carrier_max_alt_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal carrierMaxAltAmount;

    @Column(name = "carrier_max_alt_currency", nullable = false, length = 3)
    private String carrierMaxAltCurrency;

    /**
     * ¿El transportista liquida el IVA de este destino con SU número fiscal? Solo tiene sentido donde
     * existe un régimen de importación prepagable —hoy el IOSS de la UE—, y es lo que se le pide envío a
     * envío con el servicio adicional correspondiente. Todos los destinos activos están en DDP, así que
     * sin esta marca se pediría el prepago también para Estados Unidos o Brasil, donde no existe.
     */
    @Column(name = "carrier_prepays_vat", nullable = false)
    private boolean carrierPrepaysVat;

    /**
     * Qué servicio adicional hay que pedirle al transportista para que prepague, o {@code null} si el
     * canal ya va DDP por contrato y no hay que pedirle nada.
     *
     * <p>Vive por país y no en la configuración porque NO es el mismo en todas partes: {@code V1} es, por
     * definición del transportista, el prepago del IOSS de la UE, y mandarlo a un destino de fuera hace
     * fallar el alta del envío. En Emiratos, Arabia Saudí, Canadá y México el canal liquida solo.
     */
    @Column(name = "vat_prepay_service_code", length = 16)
    private String vatPrepayServiceCode;

    @Column(nullable = false)
    private boolean active;
}
