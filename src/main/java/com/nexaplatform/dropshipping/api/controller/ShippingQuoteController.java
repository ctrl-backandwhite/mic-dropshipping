package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.CountryTaxService;
import com.nexaplatform.dropshipping.application.service.CheckoutPreviewService;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.ShippingQuoteService;
import com.nexaplatform.dropshipping.domain.enums.PostalCodeFormat;
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
            String couponCode,
            /**
             * Forma de envío elegida en el selector del checkout. Nulo = la más barata, que es lo que
             * cotiza la primera vez. Se revalida contra la cotización recién hecha: un canal que ya no
             * está no abarata el envío, se cae a la más barata (ver {@code ShippingOptionResolver}).
             */
            String shippingOptionCode) {
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
            /**
             * Formas de envío entre las que el cliente puede elegir, de más barata a más cara y ya sin
             * las que no pueden cumplir el DDP. Vacía cuando la tarifa sale de la tabla de zonas: ahí no
             * hay entre qué elegir. El precio de cada una ya va en la divisa del comprador.
             */
            List<ShippingOptionOut> options,
            /**
             * La forma de envío con la que está calculado ESTE desglose. No tiene por qué ser la que
             * pidió el cliente: si el canal ya no cotiza se cobra la más barata, y el checkout necesita
             * saberlo para marcar la que de verdad se está pagando. Nulo cuando no hay opciones.
             */
            String selectedShippingOptionCode,
            /** Subtotal de producto (con el margen del país ya aplicado), céntimos USD. */
            int subtotalUsdCents,
            /** Recargo de despacho de aduana incluido en el envío (p. ej. 3 EUR/artículo en la UE), céntimos USD. */
            int customsHandlingUsdCents,
            /** Envío SIN el recargo de aduana, ya formateado (para separarlo de "Aranceles UE" en el checkout). */
            String shippingBaseFormatted,
            /** Recargo de aduana ("Aranceles UE") ya formateado; "" si no aplica. */
            String customsHandlingFormatted,
            /**
             * Subtotal de producto ya formateado en la divisa del comprador.
             *
             * <p>Es la contrapartida imprescindible del redondeo por línea: el importe de cada línea se
             * calcula en dólares y se convierte UNA sola vez, así que ya NO es «unitario × cantidad». Sin
             * publicar el subtotal y el importe de cada línea, el cliente que sume lo que ve en pantalla no
             * llega al total y cree que le están cobrando de más.
             */
            String subtotalFormatted,
            /** Una entrada por línea del carrito, con su unitario y su importe, ya formateados. */
            List<QuoteLine> items) {
    }

    /**
     * Línea del resumen. El importe NO es el unitario multiplicado por la cantidad: sale de convertir el
     * canónico de la línea entera, redondeando una única vez. Por eso viaja calculado desde el servidor.
     */
    /**
     * Una forma de envío ofrecida al cliente. {@code code} es lo que hay que devolver al pagar; el
     * nombre del canal del transportista NO se enseña —«云途全球服装专线挂号» no le dice nada a nadie—,
     * lo pinta el front con su propio texto a partir del plazo y el precio.
     */
    public record ShippingOptionOut(String code, int amountUsdCents, String amountFormatted,
            int etaMinDays, int etaMaxDays) {
    }

    public record QuoteLine(UUID productId, UUID variantId, int quantity, String unitFormatted,
            String lineTotalFormatted) {
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
                userId, req.couponCode(), req.shippingOptionCode());

        ShippingQuote q = preview.quote();
        String code = pricingService.displayCurrencyCode();
        // Separar el envío en "Envío base" y "Aranceles UE": el recargo de aduana se convierte a la divisa
        // y el envío base = envío total − aduana, para que la suma cuadre exactamente con el envío mostrado.
        int customsCents = preview.totals().customsHandlingCents();
        BigDecimal customsDisplay = currencyService.usdToDisplay(BigDecimal.valueOf(customsCents).movePointLeft(2))
                .setScale(2, RoundingMode.HALF_UP);
        String customsFmt = customsCents > 0 ? currencyService.formatDisplay(customsDisplay, code) : "";
        String shippingBaseFmt = currencyService.formatDisplay(
                preview.shippingDisplay().subtract(customsDisplay), code);
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
                q.options().stream()
                        .map(o -> new ShippingOptionOut(o.code(), o.amountUsdCents(),
                                currencyService.formatDisplay(currencyService.usdToDisplay(
                                        java.math.BigDecimal.valueOf(o.amountUsdCents(), 2)), code),
                                o.etaMinDays(), o.etaMaxDays()))
                        .toList(),
                preview.shippingOption() != null ? preview.shippingOption().code() : null,
                preview.subtotalUsdCents(), preview.totals().customsHandlingCents(),
                shippingBaseFmt, customsFmt,
                currencyService.formatDisplay(preview.subtotalDisplay(), code),
                preview.lines().stream()
                        .map(l -> new QuoteLine(l.productId(), l.variantId(), l.quantity(), l.unitFormatted(),
                                l.lineSubtotalFormatted()))
                        .toList());
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Países a los que se puede enviar (cobertura real del transportista)")
    @GetMapping("/countries")
    public ResponseEntity<List<SupportedCountry>> supportedCountries() {
        return ResponseEntity.ok(shippingQuoteService.supportedCountries());
    }

    /**
     * Qué código postal espera un país: la expresión que lo describe y un ejemplo real.
     *
     * <p>{@code pattern} y {@code example} van vacíos, y {@code required} a false, cuando el país no
     * tiene formato conocido —hay países sin código postal— y por tanto no hay nada que exigir.
     */
    public record PostalFormatOut(String countryCode, boolean required, String pattern, String example) {
    }

    /**
     * El formato del código postal del país, para que el formulario avise ANTES de enviar.
     *
     * <p>Se publica desde aquí en vez de copiar la tabla en el navegador: si hubiera dos, una acabaría
     * corregida y la otra no, y el formulario rechazaría direcciones que el servidor acepta —o al revés,
     * que es peor—. La comprobación que manda sigue siendo la del servidor; esto solo es cortesía.
     */
    @Operation(summary = "Formato de código postal esperado por el país (para validar el formulario)")
    @GetMapping("/postal-format")
    public ResponseEntity<PostalFormatOut> postalFormat(@RequestParam(required = false) String country) {
        PostalCodeFormat format = PostalCodeFormat.of(country);
        String code = country == null ? "" : country.trim().toUpperCase(java.util.Locale.ROOT);
        if (format == null) {
            return ResponseEntity.ok(new PostalFormatOut(code, false, "", ""));
        }
        return ResponseEntity.ok(new PostalFormatOut(code, true, format.pattern(), format.example()));
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
