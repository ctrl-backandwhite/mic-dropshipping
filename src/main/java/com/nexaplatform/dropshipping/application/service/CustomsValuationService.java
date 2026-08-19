package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.enums.OverThresholdPolicy;
import com.nexaplatform.dropshipping.domain.enums.TaxMode;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CountryCustomsRuleEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CountryCustomsRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * Valoración aduanera del pedido según la regla del país de destino ({@code country_customs_rule}).
 *
 * <p>Resuelve las tres cosas que hacen que un envío DDP cuadre entre lo que se cobra al cliente y lo que
 * el transportista repercute después:
 *
 * <ol>
 *   <li><b>Valor declarado</b> = valor INTRÍNSECO de los bienes, es decir lo que el cliente paga por el
 *       producto (subtotal − descuento), NO el coste de compra al proveedor. Declarar el coste haría que el
 *       transportista liquidara menos impuesto del cobrado (diferencia retenida que no corresponde) además
 *       de ser infradeclaración en aduana.</li>
 *   <li><b>Umbral de minimis</b> por país (150 EUR en la UE vía IOSS, 135 GBP en UK, 3.000 NOK en Noruega,
 *       1.000 AUD en Australia...). Por encima, el régimen simplificado no aplica: despacho formal y
 *       aranceles. La política por país decide si se recarga, se absorbe o se bloquea el pedido.</li>
 *   <li><b>Handling fee del DDP</b>: el recargo del transportista por adelantar el impuesto (fijo por
 *       paquete + porcentaje sobre el impuesto). Sin esto en el precio, cada pedido come margen en
 *       silencio hasta que se concilia la factura mensual.</li>
 * </ol>
 *
 * <p><b>Base imponible:</b> el handling fee NO entra en la base del IVA. El impuesto se calcula sobre
 * (subtotal − descuento + envío) y el recargo se suma después al envío; así se evita la circularidad
 * (recargo que depende del impuesto que dependería del recargo) y se refleja que el transportista liquida
 * el impuesto sobre el valor declarado, no sobre su propia comisión.
 *
 * <p>Sin fila para el país el resultado es neutro: DDP, sin umbral evaluado y sin recargos. Es decir, el
 * comportamiento previo a esta funcionalidad — nunca encarece un destino por falta de configuración.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomsValuationService {

    private final CountryCustomsRuleRepository repository;
    private final CurrencyRateService currencyService;

    /**
     * Resultado de valorar un pedido para su despacho en aduana.
     *
     * @param countryCode         país de destino (ISO-2)
     * @param taxMode             DDP (impuestos incluidos) o DDU (los paga el destinatario)
     * @param intrinsicValueCents valor de los bienes que paga el cliente, céntimos USD — lo que se declara
     * @param deMinimisExceeded   true si supera el umbral del régimen simplificado del país
     * @param policy              qué hacer al superar el umbral
     * @param handlingFeeCents    recargo total del despacho a sumar al envío, céntimos USD
     * @param blocked             true si la política del país impide vender ese pedido a ese destino
     * @param deMinimisLabel      el límite que bloquea, ya formateado ("150 EUR"); "" si no hay
     * @param carrierPrepaysVat   true si el transportista liquida el IVA del destino con SU número fiscal
     * @param vatPrepayServiceCode servicio adicional que hay que pedirle para ello, o {@code null} si el
     *                             canal ya va DDP por contrato y no hay que pedirle nada
     */
    public record CustomsValuation(String countryCode, TaxMode taxMode, int intrinsicValueCents,
            boolean deMinimisExceeded, OverThresholdPolicy policy, int handlingFeeCents, boolean blocked,
            String deMinimisLabel, boolean carrierPrepaysVat, String vatPrepayServiceCode) {

        /**
         * Valoración sin servicio de prepago que pedir.
         *
         * <p>Es lo que corresponde a la inmensa mayoría de los destinos —los que no tienen prepago— y a
         * los que lo tienen por contrato del canal, donde el transportista liquida sin que haya que
         * pedirle nada. Mantiene además funcionando lo escrito cuando el código del servicio salía de la
         * configuración global y no de la fila del país.
         */
        public CustomsValuation(String countryCode, TaxMode taxMode, int intrinsicValueCents,
                boolean deMinimisExceeded, OverThresholdPolicy policy, int handlingFeeCents,
                boolean blocked, String deMinimisLabel, boolean carrierPrepaysVat) {
            this(countryCode, taxMode, intrinsicValueCents, deMinimisExceeded, policy, handlingFeeCents,
                    blocked, deMinimisLabel, carrierPrepaysVat, null);
        }

        /** Valor a declarar en aduana (céntimos USD). Hoy coincide con el valor intrínseco de los bienes. */
        public int declaredValueCents() {
            return intrinsicValueCents;
        }
    }

    /** Valoración neutra: sin regla configurada para el país no se altera nada del cálculo actual. */
    private static CustomsValuation neutral(String countryCode, int intrinsicValueCents) {
        return new CustomsValuation(countryCode, TaxMode.DDP, intrinsicValueCents, false,
                OverThresholdPolicy.SURCHARGE, 0, false, "", false, null);
    }

    /**
     * Valora el pedido para el país de destino.
     *
     * @param countryCode         país de destino (ISO-2)
     * @param intrinsicValueCents valor de los bienes que paga el cliente (subtotal − descuento), céntimos USD
     * @param taxCents            impuesto ya calculado sobre la base imponible, céntimos USD
     * @param parcels             los bultos que se van a declarar, con sus partidas arancelarias. Cada bulto
     *                            es un envío a efectos de aduana: el derecho se cobra por sus líneas y el
     *                            umbral de franquicia se mide sobre SU valor, no sobre el del pedido entero
     *                            (ver {@link CustomsDutyLinesService})
     */
    @Transactional(readOnly = true)
    public CustomsValuation valuate(String countryCode, int intrinsicValueCents, int taxCents,
            List<CustomsDutyLinesService.DutyParcel> parcels) {
        int intrinsic = Math.max(0, intrinsicValueCents);
        List<CustomsDutyLinesService.DutyParcel> bultos = parcels == null ? List.of() : parcels;
        Optional<CountryCustomsRuleEntity> found = activeRule(countryCode);
        if (found.isEmpty()) {
            return neutral(countryCode, intrinsic);
        }
        CountryCustomsRuleEntity r = found.get();
        TaxMode mode = TaxMode.from(r.getTaxMode());
        OverThresholdPolicy policy = OverThresholdPolicy.from(r.getOverThresholdPolicy());
        // La franquicia se mide sobre el VALOR INTRÍNSECO DEL PEDIDO COMPLETO, nunca bulto a bulto.
        //
        // Se comprobaba por bulto, y eso abría dos agujeros. El fiscal: la UE AGREGA los envíos del mismo
        // pedido al mismo destinatario, así que un pedido de 400 EUR repartido en cuatro bultos de 100 no
        // deja de superar los 150 — declararlo como cuatro envíos de bajo valor es infradeclaración, y la
        // deuda es del declarante. Es exactamente lo que advierte el javadoc de ParcelSplitter, que
        // afirmaba que el umbral se evaluaba sobre el pedido entero mientras aquí se hacía lo contrario.
        // Y el de negocio: los 52 países con umbral están en BLOCK, de modo que evaluar por bulto dejaba
        // PASAR Y COBRAR pedidos que la política manda rechazar, con solo superar el tope de peso o de
        // valor del canal para que se partieran.
        //
        // El reparto en bultos sigue importando para el DERECHO por partida (más abajo), que sí se cuenta
        // por declaración. Son dos cosas distintas: la franquicia mira el envío; el derecho, cada bulto.
        boolean exceeded = exceedsDeMinimis(r, intrinsic);
        // Dos motivos independientes para no dejar comprar: que la política del país lo prohíba por
        // encima de la franquicia, o que el TRANSPORTISTA no acepte ese valor. El segundo es más
        // estricto —rechaza «igual o mayor», no «mayor»—, así que un pedido en el borde exacto de la
        // franquicia está dentro del régimen fiscal y fuera de lo que el carrier transporta.
        boolean blocked = (exceeded && policy == OverThresholdPolicy.BLOCK) || carrierRejects(r, intrinsic);

        // El recargo solo existe en DDP: en DDU el impuesto y su gestión los asume el destinatario.
        int handling = 0;
        if (mode == TaxMode.DDP) {
            handling = r.getHandlingFeeCents() + percentOf(Math.max(0, taxCents), r.getHandlingPercentBps());
            // Comisión del prepago de IVA (sin IOSS): % sobre el VALOR DECLARADO, además del IVA.
            handling += percentOf(intrinsic, r.getVatPrepayPercentBps());
            // Derecho temporal de la UE: tarifa × nº de LÍNEAS DE DECLARACIÓN (partidas arancelarias
            // distintas), contadas dentro de cada bulto y solo en los bultos que no superan la franquicia
            // —por encima de ella no se aplica el importe fijo, sino el arancel normal del TARIC—.
            int perLine = toUsdCents(r.getPerArticleFeeAmount(), r.getPerArticleFeeCurrency());
            if (perLine > 0) {
                for (CustomsDutyLinesService.DutyParcel bulto : bultos) {
                    if (!exceedsDeMinimis(r, bulto.valueCents())) {
                        handling += Math.multiplyExact(bulto.tariffLines(), perLine);
                    }
                }
            }
            if (exceeded && policy == OverThresholdPolicy.SURCHARGE) {
                handling += r.getOverThresholdSurchargeCents() + percentOf(intrinsic, r.getDutyRateBps());
            }
        }
        return new CustomsValuation(countryCode, mode, intrinsic, exceeded, policy, Math.max(0, handling),
                blocked, blockingLimitLabel(r, intrinsic), r.isCarrierPrepaysVat(),
                r.getVatPrepayServiceCode());
    }

    /**
     * El umbral del país en su divisa legal, formateado para el mensaje al cliente ("150 EUR"). Vacío si
     * el país no tiene franquicia configurada. Se muestra en su divisa LEGAL (la ley lo fija así: 150 € en
     * la UE), no en la divisa activa del comprador.
     */
    private static String thresholdLabel(CountryCustomsRuleEntity rule) {
        return amountLabel(rule.getDeMinimisAmount(), rule.getDeMinimisCurrency());
    }

    /**
     * Etiqueta del límite que hay que enseñarle al cliente cuando NO puede comprar.
     *
     * <p>No siempre es la franquicia aduanera: si quien rechaza es el transportista, el importe que
     * bloquea es el suyo, y es MENOR o igual. Enseñar el fiscal en ese caso escribiría un mensaje que se
     * contradice solo —«tu pedido supera el límite de 150 EUR» sobre un pedido de exactamente 150 EUR—,
     * así que se muestra el tope que de verdad lo ha impedido.
     */
    private String blockingLimitLabel(CountryCustomsRuleEntity rule, int intrinsicValueCents) {
        if (!carrierRejects(rule, intrinsicValueCents)) {
            return thresholdLabel(rule);
        }
        int primary = toUsdCents(rule.getCarrierMaxAmount(), rule.getCarrierMaxCurrency());
        int alternate = toUsdCents(rule.getCarrierMaxAltAmount(), rule.getCarrierMaxAltCurrency());
        boolean mandaElAlternativo = alternate > 0 && (primary <= 0 || alternate < primary);
        return mandaElAlternativo
                ? amountLabel(rule.getCarrierMaxAltAmount(), rule.getCarrierMaxAltCurrency())
                : amountLabel(rule.getCarrierMaxAmount(), rule.getCarrierMaxCurrency());
    }

    /** "150 EUR" a partir de importe y divisa; vacío si no hay importe. */
    private static String amountLabel(BigDecimal amount, String currency) {
        if (amount == null || amount.signum() <= 0) {
            return "";
        }
        return amount.stripTrailingZeros().toPlainString() + " " + (currency != null ? currency : "EUR");
    }

    /** Arancel por artículo del país en céntimos USD (0 si no aplica). Para el desglose admin de la ficha. */
    @Transactional(readOnly = true)
    public int perArticleFeeUsdCents(String countryCode) {
        return activeRule(countryCode)
                .map(r -> toUsdCents(r.getPerArticleFeeAmount(), r.getPerArticleFeeCurrency()))
                .orElse(0);
    }

    /** Modo de despacho fiscal configurado para el país (DDP si no hay regla). */
    @Transactional(readOnly = true)
    public TaxMode taxModeFor(String countryCode) {
        return activeRule(countryCode).map(r -> TaxMode.from(r.getTaxMode())).orElse(TaxMode.DDP);
    }

    /**
     * ¿El transportista liquida el IVA de este destino con su propio número fiscal? Lo consulta la
     * cotización para descartar los canales que no pueden cumplir esa promesa —los postales y los de
     * Amazon—, que además suelen ser los más baratos.
     */
    @Transactional(readOnly = true)
    public boolean carrierPrepaysVatFor(String countryCode) {
        return activeRule(countryCode).map(CountryCustomsRuleEntity::isCarrierPrepaysVat).orElse(false);
    }

    /** Regla activa del país, o vacío si no está configurado o está desactivado. */
    @Transactional(readOnly = true)
    public Optional<CountryCustomsRuleEntity> rule(String countryCode) {
        return activeRule(countryCode);
    }

    /**
     * Igual que {@link #rule(String)} pero sin anotar: es la que usan {@code valuate} y {@code taxModeFor}.
     * Llamar al método público desde dentro se salta el proxy de Spring, así que su {@code @Transactional}
     * no llegaría a aplicarse (java:S6809); la anotación queda solo en el punto de entrada.
     */
    private Optional<CountryCustomsRuleEntity> activeRule(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) {
            return Optional.empty();
        }
        return repository.findByCountryCodeIgnoreCase(countryCode.trim())
                .filter(CountryCustomsRuleEntity::isActive);
    }

    /**
     * ¿El valor intrínseco supera el umbral del régimen simplificado del país?
     *
     * <p>Un umbral de 0 significa "no configurado": no se evalúa y devuelve false, para no encarecer todos
     * los pedidos de un destino por falta de dato.
     */
    private boolean exceedsDeMinimis(CountryCustomsRuleEntity rule, int intrinsicValueCents) {
        int thresholdCents = toUsdCents(rule.getDeMinimisAmount(), rule.getDeMinimisCurrency());
        return thresholdCents > 0 && intrinsicValueCents > thresholdCents;
    }

    /**
     * ¿El transportista rechaza un envío de ese valor?
     *
     * <p>Nada que ver con la franquicia aduanera. El contrato de YunExpress no acepta paquetes
     * «iguales o mayores» a sus topes —hoy 150 EUR y 155 USD a la vez en la UE—, así que la comparación
     * es {@code >=} y gana el más restrictivo de los dos. Sin topes configurados no bloquea nada: 0
     * significa «no configurado», igual que en el resto de la tabla, y no se inventa un límite que nadie
     * ha facilitado.
     */
    private boolean carrierRejects(CountryCustomsRuleEntity rule, int intrinsicValueCents) {
        int primary = toUsdCents(rule.getCarrierMaxAmount(), rule.getCarrierMaxCurrency());
        int alternate = toUsdCents(rule.getCarrierMaxAltAmount(), rule.getCarrierMaxAltCurrency());
        int limit = Math.min(primary > 0 ? primary : Integer.MAX_VALUE,
                alternate > 0 ? alternate : Integer.MAX_VALUE);
        return limit != Integer.MAX_VALUE && intrinsicValueCents >= limit;
    }

    /**
     * Importe legal (en su divisa) → céntimos USD, con la tasa del día. Si la divisa no está en
     * {@code currency_rate} el importe se toma tal cual como USD (los importes sembrados para esas divisas
     * ya van en su equivalente USD) y se deja traza para poder corregir la configuración. Lo usan tanto el
     * umbral de minimis como el arancel por artículo.
     */
    private int toUsdCents(BigDecimal amount, String currency) {
        if (amount == null || amount.signum() <= 0) {
            return 0;
        }
        if (currency == null || currency.isBlank() || "USD".equalsIgnoreCase(currency)) {
            return toCents(amount);
        }
        if (currencyService.find(currency).isEmpty()) {
            log.warn("Umbral de minimis en divisa no disponible ({}): se interpreta como USD", currency);
            return toCents(amount);
        }
        return toCents(currencyService.toUsd(amount, currency));
    }

    private static int toCents(BigDecimal amount) {
        return amount.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    /** {@code base × bps / 10000} redondeado HALF_UP; 0 si alguno no es positivo. */
    private static int percentOf(int baseCents, int bps) {
        if (baseCents <= 0 || bps <= 0) {
            return 0;
        }
        return BigDecimal.valueOf((long) baseCents * bps).divide(BigDecimal.valueOf(10000), 0,
                RoundingMode.HALF_UP).intValue();
    }

    /* ============ Admin ============ */

    @Transactional(readOnly = true)
    public List<CountryCustomsRuleEntity> listAll() {
        return repository.findAllByOrderByCountryCodeAsc();
    }

    /**
     * Crea o actualiza la regla aduanera de un país. Los tres últimos parámetros (comisión de prepago de
     * IVA y arancel por artículo) son OPCIONALES: {@code null} = conservar el valor actual, para no pisar
     * los sembrados de la UE desde un formulario que aún no los incluya.
     */
    @Transactional
    public CountryCustomsRuleEntity upsert(CountryCustomsRuleEntity input, Integer vatPrepayPercentBps,
            BigDecimal perArticleFeeAmount, String perArticleFeeCurrency) {
        String code = input.getCountryCode().trim().toUpperCase();
        CountryCustomsRuleEntity e = repository.findByCountryCodeIgnoreCase(code)
                .orElseGet(() -> CountryCustomsRuleEntity.builder().countryCode(code).build());
        e.setCountryCode(code);
        e.setTaxMode(TaxMode.from(input.getTaxMode()).name());
        e.setDeMinimisAmount(input.getDeMinimisAmount() == null ? BigDecimal.ZERO
                : input.getDeMinimisAmount().max(BigDecimal.ZERO));
        e.setDeMinimisCurrency(input.getDeMinimisCurrency() == null || input.getDeMinimisCurrency().isBlank()
                ? "EUR" : input.getDeMinimisCurrency().trim().toUpperCase());
        e.setOverThresholdPolicy(OverThresholdPolicy.from(input.getOverThresholdPolicy()).name());
        e.setHandlingFeeCents(Math.max(0, input.getHandlingFeeCents()));
        e.setHandlingPercentBps(Math.max(0, input.getHandlingPercentBps()));
        e.setOverThresholdSurchargeCents(Math.max(0, input.getOverThresholdSurchargeCents()));
        e.setDutyRateBps(Math.max(0, input.getDutyRateBps()));
        // Opcionales: null = conservar lo que ya tiene la fila (recién creada trae 0/EUR por defecto).
        if (vatPrepayPercentBps != null) {
            e.setVatPrepayPercentBps(Math.max(0, vatPrepayPercentBps));
        }
        if (perArticleFeeAmount != null) {
            e.setPerArticleFeeAmount(perArticleFeeAmount.max(BigDecimal.ZERO));
        }
        if (perArticleFeeCurrency != null && !perArticleFeeCurrency.isBlank()) {
            e.setPerArticleFeeCurrency(perArticleFeeCurrency.trim().toUpperCase());
        }
        // Garantiza los NOT NULL para una regla nueva que no traiga estos campos.
        if (e.getPerArticleFeeAmount() == null) {
            e.setPerArticleFeeAmount(BigDecimal.ZERO);
        }
        if (e.getPerArticleFeeCurrency() == null || e.getPerArticleFeeCurrency().isBlank()) {
            e.setPerArticleFeeCurrency("EUR");
        }
        // Y los del límite de aceptación del transportista, por el mismo motivo: la columna tiene valor
        // por defecto en la BASE, pero el ORM nombra todas las columnas en el INSERT, así que el defecto
        // nunca llega a aplicarse y una regla nueva viajaba con nulos. Daba 409 al dar de alta un país —
        // editar uno existente funcionaba, y por eso pasó inadvertido—. CERO significa «sin límite»: un
        // país nuevo no hereda el tope de la UE, que quizá su línea no tiene.
        if (e.getCarrierMaxAmount() == null) {
            e.setCarrierMaxAmount(BigDecimal.ZERO);
        }
        if (e.getCarrierMaxCurrency() == null || e.getCarrierMaxCurrency().isBlank()) {
            e.setCarrierMaxCurrency("USD");
        }
        if (e.getCarrierMaxAltAmount() == null) {
            e.setCarrierMaxAltAmount(BigDecimal.ZERO);
        }
        if (e.getCarrierMaxAltCurrency() == null || e.getCarrierMaxAltCurrency().isBlank()) {
            e.setCarrierMaxAltCurrency("USD");
        }
        e.setActive(input.isActive());
        return repository.save(e);
    }
}
