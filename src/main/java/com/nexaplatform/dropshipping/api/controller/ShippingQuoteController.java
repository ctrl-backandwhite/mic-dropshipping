package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.CountryTaxService;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.ShippingQuoteService;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.CainiaoFulfillmentService.SupportedCountry;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
@RequestMapping("/api/storefront/shipping")
@RequiredArgsConstructor
public class ShippingQuoteController {

    private final ShippingQuoteService shippingQuoteService;
    private final CountryTaxService countryTaxService;
    private final PricingService pricingService;
    private final CurrencyRateService currencyService;
    private final ProductRepository productRepository;

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
     */
    public record QuoteResponse(boolean supported, String countryCode, int amountUsdCents, String carrier,
            String serviceName, int etaMinDays, int etaMaxDays, String zone, int taxRateBps,
            String shippingFormatted, String taxFormatted, String totalFormatted) {
    }

    @Operation(summary = "Cotizar envío + IVA + total del carrito para un país")
    @PostMapping("/quote")
    @Transactional(readOnly = true)
    public ResponseEntity<QuoteResponse> quote(@RequestBody QuoteRequest req) {
        List<QuoteItem> items = req.items() == null ? List.of() : req.items();
        List<ShippingQuoteService.Line> lines = items.stream()
                .map(i -> new ShippingQuoteService.Line(i.productId(), i.quantity())).toList();
        ShippingQuote q = shippingQuoteService.quote(req.country(), lines);

        String code = pricingService.displayCurrencyCode();

        // Subtotal en CÉNTIMOS USD, EXACTAMENTE como el pedido (retail redondeado a 2 dec. HACIA ARRIBA
        // × cantidad), para que el desglose del checkout CUADRE al céntimo con lo que se cobra/factura.
        // (Antes se calculaba en EUR y el IVA podía diferir 1 cént. del pedido por redondeo de divisa.)
        int subtotalUsdCents = 0;
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
            int unitCents = retail.setScale(2, RoundingMode.UP).movePointRight(2).intValueExact();
            subtotalUsdCents += unitCents * Math.max(1, it.quantity());
        }

        int shippingUsdCents = q.supported() ? q.amountUsdCents() : 0;
        // IVA resuelto por REGIÓN (estado/provincia con tasa propia → esa; si no, la nacional), sobre la
        // base imponible en céntimos USD = subtotal + envío. Idéntico al cálculo del pedido.
        int taxRateBps = countryTaxService.rateBpsFor(req.country(), req.region());
        int taxUsdCents = countryTaxService.taxCentsFor(req.country(), req.region(),
                subtotalUsdCents + shippingUsdCents);
        // Importes en la moneda activa: cada componente convertido y REDONDEADO a 2 decimales; el total
        // es la SUMA de esos componentes redondeados (igual que el detalle del pedido), para que el
        // desglose cuadre exactamente en pantalla (subtotal + envío + IVA = total) y coincida con el pedido.
        BigDecimal subDisp = currencyService.usdToDisplay(usd(subtotalUsdCents)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal shipDisp = currencyService.usdToDisplay(usd(shippingUsdCents)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal taxDisp = currencyService.usdToDisplay(usd(taxUsdCents)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalDisp = subDisp.add(shipDisp).add(taxDisp);

        QuoteResponse body = new QuoteResponse(q.supported(), q.countryCode(), q.amountUsdCents(), q.carrier(),
                q.serviceName(), q.etaMinDays(), q.etaMaxDays(), q.zone(), taxRateBps,
                currencyService.formatDisplay(shipDisp, code),
                currencyService.formatDisplay(taxDisp, code),
                currencyService.formatDisplay(totalDisp, code));
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
            @org.springframework.web.bind.annotation.RequestParam String country) {
        List<RegionOut> out = countryTaxService.regionsFor(country).stream()
                .map(r -> new RegionOut(r.getRegionCode(), r.getRegionName())).toList();
        return ResponseEntity.ok(out);
    }

    /** Céntimos USD (int) → importe USD (BigDecimal) para convertir a la moneda de display. */
    private static BigDecimal usd(int cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2);
    }
}
