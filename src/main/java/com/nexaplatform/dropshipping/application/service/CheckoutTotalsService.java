package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.application.service.CustomsValuationService.CustomsValuation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * Desglose monetario del checkout (envío, recargo de despacho, impuesto y total) en un ÚNICO sitio.
 *
 * <p>Existe para que la vista previa del checkout ({@code ShippingQuoteController}) y el cobro real
 * ({@code OrderUseCaseImpl}) usen exactamente el mismo cálculo. Antes cada uno repetía la secuencia y
 * cualquier cambio en uno descuadraba el otro al céntimo.
 *
 * <p><b>Orden del cálculo</b> (importa, y es el que refleja cómo liquida el transportista):
 * <ol>
 *   <li>Impuesto sobre la base imponible = (subtotal − descuento) + envío base.</li>
 *   <li>Valoración aduanera del destino: valor declarado, umbral de minimis y recargo del despacho DDP.</li>
 *   <li>El recargo se suma al ENVÍO, después del impuesto: el transportista liquida el impuesto sobre el
 *       valor declarado de los bienes, no sobre su propia comisión de gestión. Además evita la
 *       circularidad de un recargo que dependa del impuesto que dependería del recargo.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutTotalsService {

    private final CountryTaxService taxService;
    private final CustomsValuationService customsValuationService;

    /**
     * Desglose del checkout, todo en céntimos USD.
     *
     * @param shippingBaseCents  tarifa del transportista antes del recargo de despacho
     * @param customsHandlingCents recargo por el despacho DDP (y por despacho formal si supera el umbral)
     * @param shippingCents      envío total que se muestra y se cobra = base + recargo
     * @param taxCents           impuesto sobre (subtotal − descuento) + envío base
     * @param taxRateBps         tasa efectiva en puntos básicos (solo para la etiqueta "X%")
     * @param customs            valoración aduanera aplicada (modo fiscal, umbral, política)
     */
    /**
     * @param shippingBaseCents     el porte del transportista, SIN subvención: lo que cuesta de verdad
     * @param customsHandlingCents  el derecho de aduana ÍNTEGRO — lo que se declara y se liquida. NO baja
     *                              con la subvención: esa abarata lo que paga el cliente, nunca lo que se
     *                              declara, porque declarar de menos es infradeclarar ante 27 aduanas
     * @param shippingCents         lo que se le cobra al cliente por envío y aduana, ya subvencionado
     * @param shippingSubsidyCents  cuánto se ha gastado de la bolsa. Es lo que pinta el desglose como
     *                              «descuento en el envío»; lo que sobre de la bolsa se queda como ganancia
     */
    public record CheckoutTotals(int shippingBaseCents, int customsHandlingCents, int shippingCents,
            int taxCents, int taxRateBps, CustomsValuation customs, int shippingSubsidyCents) {

        /** Sin subvención: el atajo de los llamantes y las pruebas que no la usan. */
        public CheckoutTotals(int shippingBaseCents, int customsHandlingCents, int shippingCents,
                int taxCents, int taxRateBps, CustomsValuation customs) {
            this(shippingBaseCents, customsHandlingCents, shippingCents, taxCents, taxRateBps, customs, 0);
        }

        /** ¿La bolsa cubrió el envío entero? Es lo que decide si al cliente se le dice «envío gratis». */
        public boolean freeShipping() {
            return shippingCents == 0 && shippingSubsidyCents > 0;
        }

        /**
         * Qué porcentaje del envío y la aduana estamos cubriendo (0-100).
         *
         * <p>Se mide sobre lo que el cliente habría pagado <b>sin</b> la bolsa, que es lo que da sentido a
         * «te cubrimos el 43 %». Medirlo sobre lo que queda por pagar daría un número que sube cuanto
         * menos se cubre, y con el envío gratis sería una división por cero.
         */
        public int subsidyPercent() {
            int sinBolsa = Math.addExact(shippingBaseCents, customsHandlingCents);
            return sinBolsa <= 0 ? 0 : (int) Math.round(shippingSubsidyCents * 100.0 / sinBolsa);
        }

        /** Total a cobrar = (subtotal − descuento) + envío (con recargo) + impuesto. */
        public int totalCents(int discountedSubtotalCents) {
            return Math.addExact(Math.addExact(discountedSubtotalCents, shippingCents), taxCents);
        }

        /** true si la política del país impide vender este pedido a ese destino. */
        public boolean blocked() {
            return customs.blocked();
        }
    }

    /**
     * Calcula el desglose para un destino.
     *
     * @param country                  país de envío (ISO-2)
     * @param region                   estado/provincia para el impuesto por región (US/CA/BR); puede ser null
     * @param discountedSubtotalCents  subtotal de producto menos descuento — es también el valor intrínseco
     *                                 que se declarará en aduana
     * @param shippingBaseCents        tarifa de envío cotizada por el transportista
     * @param parcels                  bultos a declarar con sus partidas arancelarias: sobre ellos se calcula
     *                                 el derecho fijo de la UE y se mide la franquicia (uno por declaración)
     */
    /** Sin subvención. */
    @Transactional(readOnly = true)
    public CheckoutTotals compute(String country, String region, int discountedSubtotalCents,
            int shippingBaseCents, List<CustomsDutyLinesService.DutyParcel> parcels) {
        return compute(country, region, discountedSubtotalCents, shippingBaseCents, parcels, 0);
    }

    /**
     * El desglose con la bolsa de subvención aplicada.
     *
     * <p>La bolsa se come <b>primero el porte y después el arancel</b>. El orden no es indiferente para el
     * cliente: al revés vería el envío intacto y el arancel a cero, que se lee como que no hay aduana que
     * pagar — justo lo contrario de lo que ocurre.
     *
     * <p>Dos cosas NO cambian con la subvención, y son las dos que se liquidan con terceros: el
     * <b>derecho de aduana</b>, que se declara y se remite íntegro, y el <b>impuesto</b>, que lo fija la
     * base imponible real —mercancía más porte— y no lo que acabemos regalando. Bajar cualquiera de los
     * dos sería liquidar de menos.
     *
     * @param shippingSubsidyCents lo que hay en la bolsa (ver {@link ShippingSubsidyService}); lo que
     *                             sobre tras cubrir envío y arancel se queda como ganancia, no se devuelve
     */
    @Transactional(readOnly = true)
    public CheckoutTotals compute(String country, String region, int discountedSubtotalCents,
            int shippingBaseCents, List<CustomsDutyLinesService.DutyParcel> parcels,
            int shippingSubsidyCents) {
        int base = Math.max(0, shippingBaseCents);
        int intrinsic = Math.max(0, discountedSubtotalCents);
        int taxableBase = Math.addExact(intrinsic, base);

        int taxRateBps = taxService.rateBpsFor(country, region);
        int taxCents = taxService.taxCentsFor(country, region, taxableBase);

        CustomsValuation customs = customsValuationService.valuate(country, intrinsic, taxCents, parcels);
        int handling = customs.handlingFeeCents();
        if (log.isDebugEnabled() && (handling > 0 || customs.deMinimisExceeded())) {
            log.debug("Despacho {}: modo={} declarado={} umbralSuperado={} recargo={}", country,
                    customs.taxMode(), customs.declaredValueCents(), customs.deMinimisExceeded(), handling);
        }
        // El impuesto ya está calculado sobre la base REAL, así que la bolsa solo toca lo que se cobra.
        int aCobrar = Math.addExact(base, handling);
        int bolsa = Math.max(0, shippingSubsidyCents);
        int aplicado = Math.min(bolsa, aCobrar);
        return new CheckoutTotals(base, handling, aCobrar - aplicado, taxCents, taxRateBps, customs,
                aplicado);
    }
}
