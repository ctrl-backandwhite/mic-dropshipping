package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.application.service.CustomsValuationService.CustomsValuation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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
    public record CheckoutTotals(int shippingBaseCents, int customsHandlingCents, int shippingCents,
            int taxCents, int taxRateBps, CustomsValuation customs) {

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
     */
    @Transactional(readOnly = true)
    public CheckoutTotals compute(String country, String region, int discountedSubtotalCents,
            int shippingBaseCents, int articleCount) {
        int base = Math.max(0, shippingBaseCents);
        int intrinsic = Math.max(0, discountedSubtotalCents);
        int taxableBase = Math.addExact(intrinsic, base);

        int taxRateBps = taxService.rateBpsFor(country, region);
        int taxCents = taxService.taxCentsFor(country, region, taxableBase);

        CustomsValuation customs = customsValuationService.valuate(country, intrinsic, taxCents, articleCount);
        int handling = customs.handlingFeeCents();
        if (log.isDebugEnabled() && (handling > 0 || customs.deMinimisExceeded())) {
            log.debug("Despacho {}: modo={} declarado={} umbralSuperado={} recargo={}", country,
                    customs.taxMode(), customs.declaredValueCents(), customs.deMinimisExceeded(), handling);
        }
        return new CheckoutTotals(base, handling, Math.addExact(base, handling), taxCents, taxRateBps,
                customs);
    }
}
