package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.CountryTaxService;
import com.nexaplatform.dropshipping.application.service.CheckoutPreviewService;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.ShippingQuoteService;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.SupportedCountry;
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

    private final CheckoutPreviewService checkoutPreview;
    /** Los usan los endpoints de cobertura y regiones, que son consultas directas sin cálculo. */
    private final ShippingQuoteService shippingQuoteService;
    private final CountryTaxService countryTaxService;
    private final PricingService pricingService;
    private final CurrencyRateService currencyService;

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
    public record QuoteRequest(String country, String region, List<QuoteItem> items,
            /** Código de cupón que el cliente ha tecleado. Nulo o vacío = sin cupón. */
            String couponCode) {
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
            boolean customsThresholdExceeded, boolean customsBlocked, String taxMode,
            /** Umbral de importación del país en su divisa legal ("150 EUR"); "" si no aplica. */
            String customsLimit,
            /**
             * Cupón: el código aplicado, o el motivo por el que no vale. Se devuelven los dos para que
             * el checkout distinga «canjeado» de «rechazado y por qué» sin adivinarlo del importe.
             */
            String couponCode, String couponError,
            /** Subtotal de producto (con el margen del país ya aplicado), céntimos USD. */
            int subtotalUsdCents,
            /** Recargo de despacho de aduana incluido en el envío (p. ej. 3 EUR/artículo en la UE), céntimos USD. */
            int customsHandlingUsdCents) {
    }

    @Operation(summary = "Cotizar envío + IVA + total del carrito para un país")
    @PostMapping("/quote")
    @Transactional(readOnly = true)
    public ResponseEntity<QuoteResponse> quote(@RequestBody QuoteRequest req, Authentication auth) {
        List<QuoteItem> items = req.items() == null ? List.of() : req.items();
        UUID userId = auth != null ? parseUserId(auth.getName()) : null;
        CheckoutPreviewService.Preview preview = checkoutPreview.compute(req.country(), req.region(),
                items.stream().map(i -> new CheckoutPreviewService.Line(i.productId(), i.variantId(), i.quantity()))
                        .toList(),
                userId, req.couponCode());

        ShippingQuote q = preview.quote();
        String code = pricingService.displayCurrencyCode();
        // amountUsdCents = envío TOTAL (tarifa + recargo de despacho), que es lo que se cobrará. Si se
        // devolviera la tarifa sin recargo, el front pintaría un envío distinto del facturado.
        QuoteResponse body = new QuoteResponse(q.supported(), q.countryCode(), preview.shippingUsdCents(),
                q.carrier(), q.serviceName(), q.etaMinDays(), q.etaMaxDays(), q.zone(), preview.taxRateBps(),
                currencyService.formatDisplay(preview.shippingDisplay(), code),
                currencyService.formatDisplay(preview.taxDisplay(), code),
                currencyService.formatDisplay(preview.totalDisplay(), code),
                preview.discountUsdCents(),
                currencyService.formatDisplay(preview.discountDisplay(), code),
                preview.totals().customs().deMinimisExceeded(), preview.totals().blocked(),
                preview.totals().customs().taxMode().name(),
                preview.totals().customs().deMinimisLabel(),
                preview.couponCode(), preview.couponError(),
                preview.subtotalUsdCents(), preview.totals().customsHandlingCents());
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Países a los que se puede enviar (cobertura real del transportista)")
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
}
