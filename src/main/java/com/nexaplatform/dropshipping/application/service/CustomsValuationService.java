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
     * @param deMinimisLabel      el umbral del país en su divisa legal, ya formateado ("150 EUR"); "" si no hay
     */
    public record CustomsValuation(String countryCode, TaxMode taxMode, int intrinsicValueCents,
            boolean deMinimisExceeded, OverThresholdPolicy policy, int handlingFeeCents, boolean blocked,
            String deMinimisLabel) {

        /** Valor a declarar en aduana (céntimos USD). Hoy coincide con el valor intrínseco de los bienes. */
        public int declaredValueCents() {
            return intrinsicValueCents;
        }
    }

    /** Valoración neutra: sin regla configurada para el país no se altera nada del cálculo actual. */
    private static CustomsValuation neutral(String countryCode, int intrinsicValueCents) {
        return new CustomsValuation(countryCode, TaxMode.DDP, intrinsicValueCents, false,
                OverThresholdPolicy.SURCHARGE, 0, false, "");
    }

    /**
     * Valora el pedido para el país de destino.
     *
     * @param countryCode         país de destino (ISO-2)
     * @param intrinsicValueCents valor de los bienes que paga el cliente (subtotal − descuento), céntimos USD
     * @param taxCents            impuesto ya calculado sobre la base imponible, céntimos USD
     */
    @Transactional(readOnly = true)
    public CustomsValuation valuate(String countryCode, int intrinsicValueCents, int taxCents) {
        int intrinsic = Math.max(0, intrinsicValueCents);
        Optional<CountryCustomsRuleEntity> found = activeRule(countryCode);
        if (found.isEmpty()) {
            return neutral(countryCode, intrinsic);
        }
        CountryCustomsRuleEntity r = found.get();
        TaxMode mode = TaxMode.from(r.getTaxMode());
        OverThresholdPolicy policy = OverThresholdPolicy.from(r.getOverThresholdPolicy());
        boolean exceeded = exceedsDeMinimis(r, intrinsic);
        boolean blocked = exceeded && policy == OverThresholdPolicy.BLOCK;

        // El recargo solo existe en DDP: en DDU el impuesto y su gestión los asume el destinatario.
        int handling = 0;
        if (mode == TaxMode.DDP) {
            handling = r.getHandlingFeeCents() + percentOf(Math.max(0, taxCents), r.getHandlingPercentBps());
            if (exceeded && policy == OverThresholdPolicy.SURCHARGE) {
                handling += r.getOverThresholdSurchargeCents() + percentOf(intrinsic, r.getDutyRateBps());
            }
        }
        return new CustomsValuation(countryCode, mode, intrinsic, exceeded, policy, Math.max(0, handling),
                blocked, thresholdLabel(r));
    }

    /**
     * El umbral del país en su divisa legal, formateado para el mensaje al cliente ("150 EUR"). Vacío si
     * el país no tiene franquicia configurada. Se muestra en su divisa LEGAL (la ley lo fija así: 150 € en
     * la UE), no en la divisa activa del comprador.
     */
    private static String thresholdLabel(CountryCustomsRuleEntity rule) {
        if (rule.getDeMinimisAmount() == null || rule.getDeMinimisAmount().signum() <= 0) {
            return "";
        }
        return rule.getDeMinimisAmount().stripTrailingZeros().toPlainString() + " "
                + (rule.getDeMinimisCurrency() != null ? rule.getDeMinimisCurrency() : "EUR");
    }

    /** Modo de despacho fiscal configurado para el país (DDP si no hay regla). */
    @Transactional(readOnly = true)
    public TaxMode taxModeFor(String countryCode) {
        return activeRule(countryCode).map(r -> TaxMode.from(r.getTaxMode())).orElse(TaxMode.DDP);
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
        int thresholdCents = thresholdUsdCents(rule.getDeMinimisAmount(), rule.getDeMinimisCurrency());
        return thresholdCents > 0 && intrinsicValueCents > thresholdCents;
    }

    /**
     * Umbral legal (en su divisa) → céntimos USD, con la tasa del día. Si la divisa no está en
     * {@code currency_rate} el importe se toma tal cual como USD (los umbrales sembrados para esas divisas
     * ya van en su equivalente USD) y se deja traza para poder corregir la configuración.
     */
    private int thresholdUsdCents(BigDecimal amount, String currency) {
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

    /** Crea o actualiza la regla aduanera de un país. */
    @Transactional
    public CountryCustomsRuleEntity upsert(CountryCustomsRuleEntity input) {
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
        e.setActive(input.isActive());
        return repository.save(e);
    }
}
