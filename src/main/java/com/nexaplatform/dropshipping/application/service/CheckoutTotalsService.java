package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.application.service.CustomsValuationService.CustomsValuation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
     * @param shippingSubsidyCents  cuánto se ha gastado de la bolsa DEL PORTE. Es lo que pinta el desglose
     *                              como «descuento en el envío»; lo que sobre se queda como ganancia
     * @param customsSubsidyCents   lo mismo para la bolsa DEL ARANCEL. Son dos bolsas independientes:
     *                              ninguna cubre el concepto de la otra
     */
    public record CheckoutTotals(int shippingBaseCents, int customsHandlingCents, int shippingCents, int taxCents,
            int taxRateBps, CustomsValuation customs, int shippingSubsidyCents, int customsSubsidyCents) {

        /** Sin subvención: el atajo de los llamantes y las pruebas que no la usan. */
        public CheckoutTotals(int shippingBaseCents, int customsHandlingCents, int shippingCents, int taxCents,
                int taxRateBps, CustomsValuation customs) {
            this(shippingBaseCents, customsHandlingCents, shippingCents, taxCents, taxRateBps, customs, 0, 0);
        }

        /** Lo que se gasta de la bolsa en total: porte más arancel. */
        public int subsidyCents() {
            return Math.addExact(shippingSubsidyCents, customsSubsidyCents);
        }

        /**
         * Lo que el cliente paga DE PORTE, ya descontada la bolsa.
         *
         * <p>El resumen enseñaba la tarifa y el descuento por separado y dejaba la resta al comprador:
         * «Envío 13,30 €» y «Subsidio −12,37 €», sin decir en ninguna parte que pagaba 0,93 €. Ese es
         * justo el número que se busca en un resumen, así que se calcula aquí —donde están los dos
         * sumandos— y no en el navegador, que no hace cuentas con dinero.
         */
        public int shippingNetCents() {
            return Math.max(0, shippingBaseCents - shippingSubsidyCents);
        }

        /** Lo que el cliente paga DE ARANCEL. Mismo motivo que {@link #shippingNetCents()}. */
        public int customsNetCents() {
            return Math.max(0, customsHandlingCents - customsSubsidyCents);
        }

        /** Qué parte del PORTE estamos cubriendo (0-100). */
        public int shippingSubsidyPercent() {
            return shippingBaseCents <= 0 ? 0 : (int) Math.round(shippingSubsidyCents * 100.0 / shippingBaseCents);
        }

        /** Qué parte del ARANCEL estamos cubriendo (0-100). */
        public int customsSubsidyPercent() {
            return customsHandlingCents <= 0 ? 0 : (int) Math.round(customsSubsidyCents * 100.0 / customsHandlingCents);
        }

        /** ¿La bolsa cubrió el PORTE entero? Es lo que decide si al cliente se le dice «envío gratis». */
        public boolean freeShipping() {
            return shippingBaseCents > 0 && shippingSubsidyCents >= shippingBaseCents;
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
            return sinBolsa <= 0 ? 0 : (int) Math.round(subsidyCents() * 100.0 / sinBolsa);
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
    /**
     * Las dos bolsas de subvención del pedido, en céntimos de dólar.
     *
     * <p>Van en un record y no como dos {@code int} sueltos en la firma a propósito: son dos enteros
     * contiguos del mismo tipo y, sueltos, nada impide intercambiarlos al llamar —y el compilador no
     * diría nada—. Aquí hay que nombrar cada uno.
     */
    public record Subsidy(int shippingCents, int dutyCents) {

        /** Sin subvención: el atajo de los llamantes y las pruebas que no la usan. */
        public static final Subsidy NONE = new Subsidy(0, 0);
    }

    /** Sin subvención. */
    @Transactional(readOnly = true)
    public CheckoutTotals compute(String country, String region, int discountedSubtotalCents, int shippingBaseCents,
            List<CustomsDutyLinesService.DutyParcel> parcels) {
        return compute(country, region, discountedSubtotalCents, shippingBaseCents, parcels, Subsidy.NONE);
    }

    /**
     * El desglose con las dos bolsas de subvención aplicadas.
     *
     * <p><b>Cada bolsa subvenciona una sola cosa.</b> La del porte cubre porte y la del arancel cubre
     * arancel; lo que sobre de una NO pasa a la otra. Hasta el 1-sep-2026 había una bolsa única que se
     * comía primero el porte y después el arancel, y ese reparto en cascada tenía sentido porque la
     * cantidad la calculaba el sistema a partir del margen, sin estar asignada a ningún concepto. Ahora
     * los dos importes los asigna el admin producto a producto: trasvasar el sobrante de uno al otro
     * haría que lo que ve el cliente dejara de deducirse de lo que se teclea.
     *
     * <p>Dos cosas NO cambian con la subvención, y son las dos que se liquidan con terceros: el
     * <b>derecho de aduana</b>, que se declara y se remite íntegro, y el <b>impuesto</b>, que lo fija la
     * base imponible real —mercancía más porte— y no lo que acabemos regalando. Bajar cualquiera de los
     * dos sería liquidar de menos.
     *
     * @param subsidy las dos bolsas (ver {@link ProductSubsidyService}); lo que sobre tras cubrir su
     *                concepto se queda como ganancia, no se devuelve ni se traspasa a la otra
     */
    @Transactional(readOnly = true)
    public CheckoutTotals compute(String country, String region, int discountedSubtotalCents, int shippingBaseCents,
            List<CustomsDutyLinesService.DutyParcel> parcels, Subsidy subsidy) {
        int base = Math.max(0, shippingBaseCents);
        int intrinsic = Math.max(0, discountedSubtotalCents);
        int taxableBase = Math.addExact(intrinsic, base);

        int taxRateBps = taxService.rateBpsFor(country, region);
        int taxCents = taxService.taxCentsFor(country, region, taxableBase);

        CustomsValuation customs = customsValuationService.valuate(country, intrinsic, taxCents, parcels);
        int handling = customs.handlingFeeCents();
        if (log.isDebugEnabled() && (handling > 0 || customs.deMinimisExceeded())) {
            log.debug("Despacho {}: modo={} declarado={} umbralSuperado={} recargo={}", country, customs.taxMode(),
                    customs.declaredValueCents(), customs.deMinimisExceeded(), handling);
        }
        // Bolsas estancas: cada una cubre SU concepto y como mucho lo que ese concepto cuesta. Sin
        // trasvase; el sobrante se queda como ganancia. Se llevan por separado además porque el resumen
        // enseña el porcentaje cubierto DE CADA UNO —«envío gratis, subsidio 100 %» y «arancel, subsidio
        // 40 %»—, y con un solo número no se puede.
        Subsidy bolsas = subsidy != null ? subsidy : Subsidy.NONE;
        int subvencionPorte = Math.min(Math.max(0, bolsas.shippingCents()), base);
        int subvencionArancel = Math.min(Math.max(0, bolsas.dutyCents()), handling);
        int porteCobrado = base - subvencionPorte;

        // El IVA se recalcula sobre lo que de VERDAD se cobra de porte. Un descuento concedido en el
        // momento de la operación no forma parte de la base imponible: si el envío sale gratis no hay
        // envío que gravar, y seguir cobrando su IVA sería repercutirle al cliente el impuesto de un
        // importe que no ha pagado. El arancel nunca estuvo en esta base y sigue sin estarlo.
        int taxCentsFinal = porteCobrado == base
                ? taxCents
                : taxService.taxCentsFor(country, region, Math.addExact(intrinsic, porteCobrado));

        return new CheckoutTotals(base, handling, porteCobrado + (handling - subvencionArancel), taxCentsFinal,
                taxRateBps, customs, subvencionPorte, subvencionArancel);
    }
}
