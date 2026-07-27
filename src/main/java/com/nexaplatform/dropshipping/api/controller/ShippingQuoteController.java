package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.AffiliateProgramService;
import com.nexaplatform.dropshipping.application.service.CheckoutTotalsService;
import com.nexaplatform.dropshipping.application.service.CountryTaxService;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.ShippingQuoteService;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.SupportedCountry;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * Cotización de envío + desglose monetario del checkout (envío, IVA y total) en la moneda activa.
 * TODO el cálculo y el formateo se hacen aquí (backend); el frontend solo pinta los strings, igual que
 * con el resto de precios (margen + tasa del día), para que el resumen coincida con lo que se cobra.
 */
@Tag(name = "Storefront · Shipping", description = "Cotización de envío Cainiao + desglose de checkout")
@RestController
@RequestMapping("/api/shipping")
@RequiredArgsConstructor
public class ShippingQuoteController {

    private final ShippingQuoteService shippingQuoteService;
    private final CountryTaxService countryTaxService;
    private final CheckoutTotalsService checkoutTotalsService;
    private final PricingService pricingService;
    private final CurrencyRateService currencyService;
    private final ProductRepository productRepository;
    private final AffiliateProgramService affiliateProgramService;

    /** UUID del usuario autenticado, o null si el nombre no es un UUID (p. ej. tokens de sistema). */
    private static UUID parseUserId(String name) {
        try {
            return name == null ? null : UUID.fromString(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public record QuoteItem(UUID productId, UUID variantId, int quantity) {
    }

    /** {@code region} = código del estado/provincia (p. ej. "CA", "ON", "SP") para el IVA por región. */
    public record QuoteRequest(String country, String region, List<QuoteItem> items) {
    }

    /** Región (estado/provincia) para el dropdown del checkout. */
    public record RegionOut(String code, String name) {
    }

    /**
     * Respuesta del checkout: datos de envío + tasa de IVA (bps, solo para la etiqueta "X%") + los importes
     * de envío, IVA y total YA formateados en la moneda activa. El front no calcula nada.
     *
     * <p>{@code customsThresholdExceeded} avisa de que el valor de los bienes supera el umbral de
     * importación del país (150 EUR en la UE, 135 GBP en UK...): el envío deja de acogerse al régimen
     * simplificado y lleva despacho formal. {@code customsBlocked} indica que ese destino no admite el
     * pedido por encima del umbral, para que el checkout lo impida antes de intentar cobrar.
     */
    public record QuoteResponse(boolean supported, String countryCode, int amountUsdCents, String carrier,
            String serviceName, int etaMinDays, int etaMaxDays, String zone, int taxRateBps,
            String shippingFormatted, String taxFormatted, String totalFormatted,
            int discountCents, String discountFormatted,
            boolean customsThresholdExceeded, boolean customsBlocked, String taxMode) {
    }

    @Operation(summary = "Cotizar envío + IVA + total del carrito para un país")
    @PostMapping("/quote")
    @Transactional(readOnly = true)
    public ResponseEntity<QuoteResponse> quote(@RequestBody QuoteRequest req, Authentication auth) {
        List<QuoteItem> items = req.items() == null ? List.of() : req.items();
        List<ShippingQuoteService.Line> lines = items.stream()
                .map(i -> new ShippingQuoteService.Line(i.productId(), i.variantId(), i.quantity())).toList();
        ShippingQuote q = shippingQuoteService.quote(req.country(), lines);

        String code = pricingService.displayCurrencyCode();

        // Subtotal en CÉNTIMOS USD (canónico, para el descuento y la base del IVA), y subtotal en la
        // MONEDA MOSTRADA calculado POR LÍNEA (unidad convertida y redondeada a 2 dec. × cantidad, sumado),
        // EXACTAMENTE igual que el carrito (/cart-quote), el detalle del pedido, la lista y la factura. Así
        // el desglose cuadra al céntimo en TODAS las vistas (antes el preview convertía el subtotal de una
        // sola vez → "round(total)" ≠ "round(unidad)×qty" del resto, y salía 1 cént. de diferencia).
        int subtotalUsdCents = 0;
        BigDecimal subDispAcc = BigDecimal.ZERO;
        for (QuoteItem it : items) {
            if (it == null || it.productId() == null) {
                continue;
            }
            ProductEntity p = productRepository.findById(it.productId()).orElse(null);
            if (p == null) {
                continue;
            }
            ProductVariantEntity v = it.variantId() == null ? null
                    : p.getVariants().stream().filter(x -> it.variantId().equals(x.getId())).findFirst().orElse(null);
            BigDecimal retail = pricingService.priceFor(p, v).retailUsd();
            if (retail == null) {
                continue;
            }
            // HALF_UP (céntimo más cercano) — el MISMO redondeo que el catálogo y que el pedido
            // (OrderUseCaseImpl), para que catálogo == carrito == preview == cobro, sin descuadre de 1 cént.
            int unitCents = retail.setScale(2, RoundingMode.HALF_UP).movePointRight(2).intValueExact();
            // Cantidad acotada al MISMO máximo que el checkout (OrderUseCaseImpl.MAX_LINE_QUANTITY = 100.000)
            // y aritmética con desbordamiento controlado: sin esto, una cantidad enorme desbordaba el int y
            // la base del IVA salía negativa → IVA 0 en el preview (incoherente con el cobro real).
            int qty = Math.min(Math.max(1, it.quantity()), 100_000);
            subtotalUsdCents = Math.addExact(subtotalUsdCents, Math.multiplyExact(unitCents, qty));
            // Unidad en la moneda mostrada, redondeada a 2 dec., × cantidad (misma unidad que carrito/detalle).
            subDispAcc = subDispAcc.add(currencyService.usdToDisplay(usd(unitCents)).multiply(BigDecimal.valueOf(qty)));
        }

        // Descuento de referido del COMPRADOR (10% del subtotal de producto) si tiene atribución de
        // afiliado viva y NO es su propio código. Mismo cálculo que el pedido (AffiliateProgramService),
        // para que el total mostrado coincida al céntimo con lo que se cobra. Anónimo → sin descuento.
        UUID userId = auth != null ? parseUserId(auth.getName()) : null;
        int discountUsdCents = (int) affiliateProgramService.referralDiscountCents(userId, subtotalUsdCents);
        int discountedSubtotalUsdCents = subtotalUsdCents - discountUsdCents;

        int shippingBaseUsdCents = q.supported() ? q.amountUsdCents() : 0;
        // Impuesto + despacho aduanero por el MISMO servicio que usa el cobro (CheckoutTotalsService), para
        // que el desglose mostrado coincida al céntimo con el pedido: IVA por región/país sobre
        // (subtotal − descuento) + envío, más el recargo del despacho DDP del destino y, si el valor de los
        // bienes supera el umbral de minimis del país, el recargo por despacho formal.
        CheckoutTotalsService.CheckoutTotals totals = checkoutTotalsService.compute(req.country(), req.region(),
                discountedSubtotalUsdCents, shippingBaseUsdCents);
        int shippingUsdCents = totals.shippingCents();
        int taxRateBps = totals.taxRateBps();
        int taxUsdCents = totals.taxCents();
        // Importes en la moneda activa: cada componente convertido y REDONDEADO a 2 decimales; el total
        // es la SUMA de esos componentes redondeados (igual que el detalle del pedido), para que el
        // desglose cuadre exactamente en pantalla (subtotal − descuento + envío + IVA = total) y coincida
        // con el pedido.
        BigDecimal subDisp = subDispAcc.setScale(2, RoundingMode.HALF_UP);
        BigDecimal discDisp = currencyService.usdToDisplay(usd(discountUsdCents)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal shipDisp = currencyService.usdToDisplay(usd(shippingUsdCents)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal taxDisp = currencyService.usdToDisplay(usd(taxUsdCents)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalDisp = subDisp.subtract(discDisp).add(shipDisp).add(taxDisp);

        // amountUsdCents = envío TOTAL (tarifa + recargo de despacho), que es lo que se cobrará. Si se
        // devolviera la tarifa sin recargo, el front pintaría un envío distinto del facturado.
        QuoteResponse body = new QuoteResponse(q.supported(), q.countryCode(), shippingUsdCents, q.carrier(),
                q.serviceName(), q.etaMinDays(), q.etaMaxDays(), q.zone(), taxRateBps,
                currencyService.formatDisplay(shipDisp, code),
                currencyService.formatDisplay(taxDisp, code),
                currencyService.formatDisplay(totalDisp, code),
                discountUsdCents,
                currencyService.formatDisplay(discDisp, code),
                totals.customs().deMinimisExceeded(), totals.blocked(), totals.customs().taxMode().name());
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Países a los que se puede enviar (cobertura real de Cainiao)")
    @GetMapping("/countries")
    public ResponseEntity<List<SupportedCountry>> supportedCountries() {
        return ResponseEntity.ok(shippingQuoteService.supportedCountries());
    }

    @Operation(summary = "Regiones (estado/provincia) de un país para el dropdown del checkout")
    @GetMapping("/regions")
    public ResponseEntity<List<RegionOut>> regions(
            @RequestParam String country) {
        List<RegionOut> out = countryTaxService.regionsFor(country).stream()
                .map(r -> new RegionOut(r.getRegionCode(), r.getRegionName())).toList();
        return ResponseEntity.ok(out);
    }

    /** Céntimos USD (int) → importe USD (BigDecimal) para convertir a la moneda de display. */
    private static BigDecimal usd(int cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2);
    }
}
