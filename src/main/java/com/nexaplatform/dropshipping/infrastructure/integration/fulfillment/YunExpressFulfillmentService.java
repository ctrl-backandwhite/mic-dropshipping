package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexaplatform.dropshipping.application.service.CustomsValuationService;
import com.nexaplatform.dropshipping.application.service.CustomsValuationService.CustomsValuation;
import com.nexaplatform.dropshipping.application.service.ParcelAggregator;
import com.nexaplatform.dropshipping.application.service.ParcelSplitter;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.enums.TaxMode;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CainiaoZoneEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CainiaoZoneRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Proveedor de fulfillment con <b>YunExpress</b> (云途) — carrier ACTIVO del sistema.
 *
 * <p>Implementa {@link FulfillmentProvider} y es la única implementación cableada (Cainiao queda fuera del
 * flujo). Sigue siendo <b>mock-first</b>: con {@code nexadrop.yunexpress.enabled=false} (por defecto) o sin
 * credenciales, genera datos deterministas — cobertura/tarifa desde la tabla de zonas y un tracking simulado
 * que avanza con el tiempo — para que TODO el flujo (despacho → en camino → entregado) funcione sin API real.
 * Activándolo, las ramas {@code real*} llaman a la Open Platform vía {@link YunExpressClient}:
 * <ul>
 *   <li>tarifa: {@code /v1/price-trial/get}, sumando los conceptos de coste de cada canal;</li>
 *   <li>envío: {@code /v1/order/package/create} + suscripción al push de trazabilidad;</li>
 *   <li>seguimiento: {@code /v1/track-service/info/get}, mapeado con {@link YunExpressTrackNode};</li>
 *   <li>etiqueta y anulación: {@code /v1/order/label/get} y {@code /v1/order/cancel}.</li>
 * </ul>
 *
 * <p>La cotización cae a la tabla de zonas si YunExpress no recomienda canal, para que un problema del
 * transportista no bloquee el checkout. La creación del envío, en cambio, sí propaga el error: dar un
 * pedido por despachado sin guía real dejaría al cliente sin nada que seguir.
 *
 * <p><b>Impuestos:</b> YunExpress usa canales <b>DDP</b> (aranceles/IVA incluidos, los paga el comercio) o
 * <b>DDU</b> (los paga el destinatario), y <b>IOSS</b> para el IVA de la UE. El modo por defecto y el IOSS se
 * configuran en {@code nexadrop.yunexpress.*}; el IOSS solo viaja en el envío cuando el pedido no supera el
 * umbral de minimis del destino.
 *
 * <p>Nota de cobertura: se reutiliza la tabla {@code cainiao_shipping_zone} (datos genéricos de país: base,
 * por-kg, ETA) como fuente de zonas mientras no exista una tabla propia de canales de YunExpress.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class YunExpressFulfillmentService implements FulfillmentProvider {

    // Literales repetidos extraídos a constantes (java:S1192): una sola fuente por valor.
    private static final String SHIPPED = "SHIPPED";
    private static final String SUCCESS = "success";
    private static final String RESULT = "result";

    /** Nombre de cara al cliente (sin exponer marca del carrier), igual que hacía Cainiao. */
    private static final String CARRIER_NAME = "Standard Shipping";

    /** Prefijo de las propiedades que permiten reapuntar una ruta de la Open Platform sin tocar código. */
    private static final String ROUTE_PROPERTY_PREFIX = "nexadrop.yunexpress.path.";

    private final CainiaoZoneRepository zoneRepository;
    private final YunExpressClient client;
    private final CustomsValuationService customsValuation;
    /** Para la declaración aduanera por línea: nombre EN/ZH, partida, material, uso y peso del artículo. */
    private final ProductRepository productRepository;
    /** La tarifa de YunExpress llega en su divisa (RMB); el sistema cotiza en céntimos USD. */
    private final CurrencyRateService currencyRateService;
    /** Para saber si el entorno es productivo y, por tanto, si el modo simulado está permitido. */
    private final Environment environment;

    @Value("${nexadrop.yunexpress.enabled:false}")
    private boolean enabled;
    /** DDP = impuestos incluidos (los paga el comercio); DDU = los paga el destinatario en destino. */
    @Value("${nexadrop.yunexpress.default-tax-mode:DDP}")
    private String defaultTaxMode;
    /** Número IOSS del comercio para despacho de IVA en la UE (pedidos <=150 EUR). Vacío = sin IOSS. */
    @Value("${nexadrop.yunexpress.ioss-number:}")
    private String iossNumber;
    /** Minutos por etapa del tracking simulado (mock) — pon un valor pequeño para ver el avance en demo. */
    @Value("${nexadrop.yunexpress.mock-stage-minutes:2}")
    private long mockStageMinutes;
    /** Divisor del peso volumétrico: kg = L×W×H(cm) / divisor. 6000 es el estándar de aéreo/small parcel. */
    @Value("${nexadrop.yunexpress.volumetric-divisor:6000}")
    private double volumetricDivisor;
    /** Volumen (cm³) a partir del cual el transportista aplica peso volumétrico. Por debajo, factura el real. */
    @Value("${nexadrop.yunexpress.volumetric-min-cm3:6000}")
    private double volumetricMinCm3;
    /**
     * Producto logístico (canal) fijado a mano. Vacío = se elige automáticamente el más barato de los que
     * devuelve la simulación de tarifa. Los códigos salen de {@code /v1/basic-data/products/getlist}.
     */
    @Value("${nexadrop.yunexpress.product-code:}")
    private String productCode;
    /**
     * Grupo de producto al que se restringe la simulación (DH快递, EM经济, MA挂号, SP专线...). Vacío = todos.
     * Sirve para no cotizar por un canal que el contrato no cubre.
     */
    @Value("${nexadrop.yunexpress.product-group-code:}")
    private String productGroupCode;
    /** Formato de la etiqueta que se pide al crear el envío: PDF, ZPL o PNG. */
    @Value("${nexadrop.yunexpress.label-type:PDF}")
    private String labelType;
    /** ¿Suscribir cada guía al push de trazabilidad al crearla? Requiere el webhook registrado. */
    @Value("${nexadrop.yunexpress.tracking-subscription-enabled:true}")
    private boolean trackingSubscriptionEnabled;
    /** Modo de suscripción: A = trazabilidad completa, F = solo origen, L = solo reparto final. */
    @Value("${nexadrop.yunexpress.tracking-subscribe-type:A}")
    private String trackingSubscribeType;
    /**
     * Segundos que se espera a la simulación de tarifa. Es corto a propósito: la cotización está en el
     * camino del checkout y hay un fallback local inmediato, así que más vale tarifar por la tabla de
     * zonas que dejar al cliente esperando a que YunExpress conteste.
     */
    @Value("${nexadrop.yunexpress.quote-timeout-seconds:5}")
    private long quoteTimeoutSeconds;
    /**
     * Límites del canal para repartir el pedido en bultos. Son los del producto logístico contratado
     * (el canal de pruebas BPA no admite más de 2 kg ni más de 24 $). Con 0 no se reparte: todo el
     * pedido viaja en un único envío, que es el comportamiento anterior.
     */
    @Value("${nexadrop.yunexpress.max-parcel-weight-grams:0}")
    private int maxParcelWeightGrams;
    @Value("${nexadrop.yunexpress.max-parcel-value-cents:0}")
    private int maxParcelValueCents;
    @Value("${nexadrop.yunexpress.max-parcel-units:0}")
    private int maxParcelUnits;

    /**
     * Ruta de la Open Platform usada por el flujo (ver open.yunexpress.cn → API字典). No van cableadas:
     * se resuelven contra {@code nexadrop.yunexpress.path.*} y la ruta de la spec vigente es solo el valor
     * por defecto, así que versionar un endpoint del transportista es cambiar una property y no publicar
     * una versión (java:S1075). Sin {@code Environment} (pruebas unitarias) se usa la de la spec.
     */
    private String route(String name, String specDefault) {
        return environment == null ? specDefault
                : environment.getProperty(ROUTE_PROPERTY_PREFIX + name, specDefault);
    }

    private String pathPriceTrial() {
        return route("price-trial", "/v1/price-trial/get");
    }

    private String pathCreate() {
        return route("create", "/v1/order/package/create");
    }

    private String pathTrack() {
        return route("track", "/v1/track-service/info/get");
    }

    private String pathLabel() {
        return route("label", "/v1/order/label/get");
    }

    private String pathCancel() {
        return route("cancel", "/v1/order/cancel");
    }

    private String pathSubscribe() {
        return route("subscribe", "/v1/track-service/subscribe-by-order");
    }

    private String pathProducts() {
        return route("products", "/v1/basic-data/products/getlist");
    }

    private boolean isActive() {
        return enabled && client.hasCredentials();
    }

    /**
     * ¿Está permitido el modo simulado en este entorno? Solo fuera de producción.
     *
     * <p>El mock inventa una guía y hace avanzar el tracking solo hasta "entregado". En desarrollo es lo
     * que permite recorrer el flujo entero sin API; en producción sería un desastre silencioso: si
     * caducan las credenciales o alguien deja {@code enabled=false}, la plataforma daría por entregados
     * pedidos que nunca se enviaron —y mandaría al cliente el correo de entrega—. Por eso en producción
     * se prefiere fallar y que el pedido quede pendiente a la vista del admin.
     */
    private boolean mockAllowed() {
        return !environment.acceptsProfiles(Profiles.of("pro", "pre"));
    }

    // ── Cobertura ────────────────────────────────────────────────────────────────────────────────

    @Override
    public boolean isSupported(String countryCode) {
        return zone(countryCode).isPresent();
    }

    @Override
    public List<SupportedCountry> supportedCountries() {
        return zoneRepository.findByEnabledTrueOrderByCountryNameAsc().stream()
                .map(z -> new SupportedCountry(z.getCountryCode(), z.getCountryName())).toList();
    }

    private Optional<CainiaoZoneEntity> zone(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) {
            return Optional.empty();
        }
        return zoneRepository.findByCountryCodeIgnoreCase(countryCode.trim()).filter(CainiaoZoneEntity::isEnabled);
    }

    // ── Cotización ───────────────────────────────────────────────────────────────────────────────

    @Override
    public ShippingQuote quote(String countryCode, ParcelSpec parcel) {
        Optional<CainiaoZoneEntity> z = zone(countryCode);
        if (z.isEmpty()) {
            return ShippingQuote.unsupported(countryCode);
        }
        CainiaoZoneEntity zn = z.get();
        int chargeableGrams = chargeableWeightGrams(parcel);
        if (isActive()) {
            Integer cents = tryRealShippingCents(zn, parcel, chargeableGrams);
            if (cents != null) {
                return new ShippingQuote(true, zn.getCountryCode(), cents, CARRIER_NAME, CARRIER_NAME,
                        zn.getEtaMinDays(), zn.getEtaMaxDays(), zn.getZone());
            }
        }
        double kg = Math.max(0.1, chargeableGrams / 1000.0);
        int amount = zn.getBaseCents() + (int) Math.round(zn.getPerKgCents() * kg);
        return new ShippingQuote(true, zn.getCountryCode(), amount, CARRIER_NAME, CARRIER_NAME,
                zn.getEtaMinDays(), zn.getEtaMaxDays(), zn.getZone());
    }

    /**
     * Peso FACTURABLE del bulto en gramos: el mayor entre el peso real y el volumétrico
     * ({@code L×W×H cm / divisor}), que es como tarifa el transportista.
     *
     * <p>El volumétrico solo entra a partir de {@link #volumetricMinCm3} porque las líneas de small
     * parcel no lo aplican a bultos pequeños comprimidos en bolsa; por encima de ese volumen sí, y si
     * no lo repercutimos aquí el transportista repesa en almacén y nos factura la diferencia contra el
     * margen. Divisor y umbral son configurables: hay que ajustarlos al rate card del contrato.
     */
    int chargeableWeightGrams(ParcelSpec parcel) {
        int real = Math.max(1, parcel.weightGrams());
        double volumeCm3 = parcel.volumeCm3();
        if (volumeCm3 < volumetricMinCm3 || volumetricDivisor <= 0) {
            return real;
        }
        int volumetric = (int) Math.round(volumeCm3 / volumetricDivisor * 1000.0);
        return Math.max(real, volumetric);
    }

    /**
     * {@code package_type} de YunExpress: {@code C} = 普货 (carga general), {@code E} = 带电 (con batería),
     * {@code F} = 特货 (mercancía especial). Determina qué canales pueden cotizar el bulto.
     */
    static String packageType(ParcelSpec parcel) {
        return parcel.withBattery() ? "E" : "C";
    }

    /**
     * Un canal cotizado por {@code /v1/price-trial/get}: código y nombre del producto logístico, coste
     * total y plazo. La respuesta de YunExpress viene desglosada por CONCEPTO de coste ({@code fee_name}:
     * flete, registro, arancel...), así que se agrupa por {@code product_code} y se suman los conceptos —
     * quedarse con una sola línea cotizaría el envío por debajo del coste real.
     */
    record RateOption(String productCode, String productName, BigDecimal amount, String currency,
                      int etaMinDays, int etaMaxDays) {
    }

    /**
     * Tarifa real vía YunExpress (céntimos USD), o {@code null} si no hay canal disponible o la llamada
     * falla — en ese caso el llamante cae a la tabla de zonas y el checkout sigue funcionando.
     */
    private Integer tryRealShippingCents(CainiaoZoneEntity zone, ParcelSpec parcel, int chargeableGrams) {
        RateOption best = cheapestRate(zone.getCountryCode(), parcel, chargeableGrams);
        if (best == null) {
            return null;
        }
        BigDecimal usd = currencyRateService.toUsd(best.amount(), best.currency());
        if (usd == null) {
            log.warn("YunExpress: no se pudo convertir {} {} a USD para {}",
                    best.amount(), best.currency(), zone.getCountryCode());
            return null;
        }
        return usd.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    /**
     * Simula la tarifa y devuelve el canal más barato. Devuelve {@code null} —sin propagar el fallo— si
     * YunExpress no recomienda ninguno: la cotización nunca debe tumbar el checkout.
     */
    RateOption cheapestRate(String countryCode, ParcelSpec parcel, int chargeableGrams) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("country_code", countryCode);
        query.put("weight", BigDecimal.valueOf(Math.max(1, chargeableGrams))
                .divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP).toPlainString());
        query.put("weight_unit", "KG");
        query.put("package_type", packageType(parcel));
        if (parcel.hasDimensions()) {
            query.put("length", cm(parcel.lengthMm()));
            query.put("width", cm(parcel.widthMm()));
            query.put("height", cm(parcel.heightMm()));
            query.put("size_unit", "CM");
        }
        if (productGroupCode != null && !productGroupCode.isBlank()) {
            query.put("product_group_code", productGroupCode.trim());
        }
        try {
            JsonNode response = client.get(pathPriceTrial(), query, Duration.ofSeconds(quoteTimeoutSeconds));
            if (!response.path(SUCCESS).asBoolean(false)) {
                log.warn("YunExpress: sin tarifa para {} ({} g) -> {} {}", countryCode, chargeableGrams,
                        response.path("code").asText(""), response.path("msg").asText(""));
                return null;
            }
            List<RateOption> options = parseRates(response.path(RESULT));
            return options.stream().min(Comparator.comparing(RateOption::amount)).orElse(null);
        } catch (RuntimeException e) {
            log.warn("YunExpress: fallo simulando tarifa para {} -> {}", countryCode, e.getMessage());
            return null;
        }
    }

    /** Agrupa las líneas de coste por canal y suma sus importes; el plazo viene como "3-8" días. */
    static List<RateOption> parseRates(JsonNode result) {
        Map<String, RateOption> byProduct = new LinkedHashMap<>();
        for (JsonNode item : result) {
            String code = item.path("product_code").asText(null);
            if (code == null || code.isBlank()) {
                continue;
            }
            // convert_amount/convert_currency es el importe ya llevado a la divisa de facturación de la
            // cuenta; si no viene, se usa el calculado en la divisa original del rate card.
            BigDecimal amount = item.hasNonNull("convert_amount")
                    ? item.path("convert_amount").decimalValue() : item.path("calculate_amount").decimalValue();
            String currency = item.path("convert_currency").asText(null);
            if (currency == null || currency.isBlank()) {
                currency = item.path("currency").asText("CNY");
            }
            int[] eta = parseEtaDays(item.path("interval_day").asText(""));
            RateOption previous = byProduct.get(code);
            if (previous == null) {
                byProduct.put(code, new RateOption(code, item.path("product_name").asText(code),
                        amount, normalizeCurrency(currency), eta[0], eta[1]));
            } else {
                byProduct.put(code, new RateOption(previous.productCode(), previous.productName(),
                        previous.amount().add(amount), previous.currency(),
                        previous.etaMinDays(), previous.etaMaxDays()));
            }
        }
        return new ArrayList<>(byProduct.values());
    }

    /** YunExpress denomina RMB a lo que en ISO-4217 es CNY, que es como lo conoce el conversor. */
    private static String normalizeCurrency(String currency) {
        return "RMB".equalsIgnoreCase(currency) ? "CNY" : currency.toUpperCase();
    }

    /** Plazo declarado por el canal ("3-8", "7", vacío) a [mínimo, máximo] en días; 0 si no lo informa. */
    static int[] parseEtaDays(String intervalDay) {
        if (intervalDay == null || intervalDay.isBlank()) {
            return new int[] { 0, 0 };
        }
        String[] parts = intervalDay.trim().split("-");
        try {
            int min = Integer.parseInt(parts[0].trim());
            int max = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : min;
            return new int[] { min, max };
        } catch (NumberFormatException e) {
            return new int[] { 0, 0 };
        }
    }

    /** Milímetros a centímetros con un decimal, que es la unidad que pide la API ({@code size_unit=CM}). */
    private static String cm(int millimeters) {
        return BigDecimal.valueOf(millimeters)
                .divide(BigDecimal.valueOf(10), 1, RoundingMode.HALF_UP).toPlainString();
    }

    /** Canales contratados (código → nombre), tal cual los publica la cuenta. Para diagnóstico y admin. */
    public List<SupportedCountry> logisticsProducts() {
        JsonNode response = client.get(pathProducts(), Map.of());
        List<SupportedCountry> out = new ArrayList<>();
        JsonNode list = response.has("detail") ? response.path("detail") : response.path(RESULT).path("list");
        for (JsonNode item : list) {
            out.add(new SupportedCountry(item.path("product_code").asText(""),
                    item.path("product_name").asText("")));
        }
        return out;
    }

    // ── Crear envío ──────────────────────────────────────────────────────────────────────────────

    /**
     * Crea TODAS las guías del pedido: una por bulto.
     *
     * <p>Se reparte con {@link ParcelSplitter} según los límites del canal ({@code max-parcel-*}). Si un
     * bulto falla, se propaga el error: dejar un pedido con la mitad de los envíos creados sería peor que
     * reintentarlo entero — las guías ya creadas se pueden anular desde el panel.
     */
    @Override
    public List<FulfillmentResult> createShipments(Order order) {
        List<ParcelSplitter.Bin> bins = splitOrder(order);
        if (bins.size() > 1) {
            log.info("YunExpress: pedido {} repartido en {} bultos por los límites del canal",
                    order.getOrderNumber(), bins.size());
        }
        // También con un solo bulto se pasa por createShipmentForBin: así el envío guarda su peso y su
        // valor declarado. Delegar en createShipment() los dejaba a cero y el dato se perdía.
        warnCustomsGaps(order);
        List<FulfillmentResult> results = new ArrayList<>();
        for (int i = 0; i < bins.size(); i++) {
            results.add(createShipmentForBin(order, bins.get(i), i + 1));
        }
        return results;
    }

    /** Reparte el pedido en bultos según los límites configurados del canal. */
    ParcelSplitterBins splitOrderBins(Order order) {
        return new ParcelSplitterBins(splitOrder(order));
    }

    /** Envoltorio para poder exponer el reparto a los tests sin filtrar el tipo interno. */
    public record ParcelSplitterBins(List<ParcelSplitter.Bin> bins) {
    }

    private List<ParcelSplitter.Bin> splitOrder(Order order) {
        List<ParcelSplitter.Unit> units = new ArrayList<>();
        List<OrderItem> items = order.getItems();
        for (int line = 0; line < items.size(); line++) {
            OrderItem item = items.get(line);
            ProductEntity product = item.getProductId() != null
                    ? productRepository.findById(item.getProductId()).orElse(null) : null;
            ProductVariantEntity variant = variantOf(product, item);
            int unitWeight = product != null ? ParcelAggregator.unitWeightGrams(product, variant) : 500;
            boolean battery = product != null && ParcelAggregator.hasBattery(product);
            for (int q = 0; q < Math.max(1, item.getQuantity()); q++) {
                units.add(new ParcelSplitter.Unit(line, unitWeight, item.getUnitPriceCents(),
                        dimension(product, variant, Dimension.LENGTH),
                        dimension(product, variant, Dimension.WIDTH),
                        dimension(product, variant, Dimension.HEIGHT), battery));
            }
        }
        return ParcelSplitter.split(units,
                new ParcelSplitter.Limits(maxParcelWeightGrams, maxParcelValueCents, maxParcelUnits));
    }

    /** Qué medida del paquete se está pidiendo; el producto manda sobre la variante. */
    private enum Dimension { LENGTH, WIDTH, HEIGHT }

    private static int dimension(ProductEntity product, ProductVariantEntity variant, Dimension which) {
        Integer fromProduct = product == null ? null : switch (which) {
            case LENGTH -> product.getLengthMm();
            case WIDTH -> product.getWidthMm();
            case HEIGHT -> product.getHeightMm();
        };
        if (fromProduct != null && fromProduct > 0) {
            return fromProduct;
        }
        Integer fromVariant = variant == null ? null : switch (which) {
            case LENGTH -> variant.getLengthMm();
            case WIDTH -> variant.getWidthMm();
            case HEIGHT -> variant.getHeightMm();
        };
        return fromVariant != null && fromVariant > 0 ? fromVariant : 0;
    }

    private ProductVariantEntity variantOf(ProductEntity product, OrderItem item) {
        if (product == null || item.getVariantId() == null || product.getVariants() == null) {
            return null;
        }
        return product.getVariants().stream()
                .filter(v -> item.getVariantId().equals(v.getId())).findFirst().orElse(null);
    }

    /** Crea la guía de UN bulto concreto del pedido. */
    private FulfillmentResult createShipmentForBin(Order order, ParcelSplitter.Bin bin, int sequenceNo) {
        int etaMax = zone(order.getShippingCountry()).map(CainiaoZoneEntity::getEtaMaxDays).orElse(20);
        CustomsValuation valuation = declarationFor(order);
        if (!isActive()) {
            if (!mockAllowed()) {
                throw new FulfillmentFailure(FulfillmentFailure.Kind.TRANSIENT,
                        "YunExpress no está operativo y en producción no se generan envíos simulados");
            }
            String hex = order.getId().toString().replace("-", "").substring(0, 10).toUpperCase() + sequenceNo;
            return new FulfillmentResult(CARRIER_NAME, "YT" + hex + "YE", "YE" + hex, etaMax, sequenceNo,
                    bin.spec().weightGrams(), bin.valueCents(), productCode);
        }
        String channel = resolveProductCode(order.getShippingCountry(), bin.spec());
        // El número de cliente debe ser único por guía: el mismo para dos envíos lo rechaza el carrier.
        YunExpressRequests.CreateShipment payload = createPayload(order, bin.spec(), channel, valuation,
                order.getOrderNumber() + "-" + sequenceNo, declarationInfoOfBin(order, bin));
        JsonNode response;
        try {
            response = client.post(pathCreate(), payload);
        } catch (RuntimeException e) {
            throw FulfillmentFailure.of(e);
        }
        if (!response.path(SUCCESS).asBoolean(false)) {
            throw FulfillmentFailure.from("YunExpress rechazó el bulto " + sequenceNo + " del pedido "
                    + order.getOrderNumber() + ": " + response.path("code").asText("")
                    + " " + response.path("msg").asText(""));
        }
        JsonNode result = response.path(RESULT);
        String waybill = result.path("waybill_number").asText("");
        if (waybill.isBlank()) {
            throw new FulfillmentFailure(FulfillmentFailure.Kind.TRANSIENT,
                    "YunExpress no devolvió guía para el bulto " + sequenceNo + " del pedido "
                            + order.getOrderNumber());
        }
        subscribeTracking(waybill);
        return new FulfillmentResult(CARRIER_NAME, trackingOf(result, waybill), waybill, etaMax, sequenceNo,
                bin.spec().weightGrams(), bin.valueCents(), channel);
    }

    /** Declaración aduanera limitada a lo que viaja en ESTE bulto. */
    private List<YunExpressRequests.DeclarationLine> declarationInfoOfBin(Order order, ParcelSplitter.Bin bin) {
        List<ParcelDeclaration> all = declaredParcels(order);
        List<YunExpressRequests.DeclarationLine> lines = new ArrayList<>();
        for (int line = 0; line < all.size(); line++) {
            int qty = bin.quantityOfLine(line);
            if (qty > 0) {
                lines.add(toDeclarationLine(all.get(line), qty));
            }
        }
        return lines;
    }

    /** Deja constancia de lo que le falta a la declaración para pasar aduana sin fricción. */
    private void warnCustomsGaps(Order order) {
        List<String> gaps = customsGaps(declaredParcels(order));
        if (!gaps.isEmpty()) {
            log.warn("Pedido {}: declaración aduanera incompleta para YunExpress -> {}",
                    order.getOrderNumber(), gaps);
        }
    }

    /**
     * Crea el envío del pedido como UN solo bulto, sin repartir. Se conserva para las pruebas de
     * integración contra el sandbox; el flujo real entra por {@link #createShipments(Order)}, que es
     * quien rellena peso, valor declarado y canal de cada bulto.
     */
    public FulfillmentResult createShipment(Order order) {
        int etaMax = zone(order.getShippingCountry()).map(CainiaoZoneEntity::getEtaMaxDays).orElse(20);
        CustomsValuation valuation = declarationFor(order);
        List<ParcelDeclaration> parcels = declaredParcels(order);
        warnCustomsGaps(order);
        if (!isActive()) {
            if (!mockAllowed()) {
                throw new FulfillmentFailure(FulfillmentFailure.Kind.TRANSIENT,
                        "YunExpress no está operativo (enabled=" + enabled + ", credenciales="
                                + client.hasCredentials() + ") y en producción no se generan envíos simulados");
            }
            String hex = order.getId().toString().replace("-", "").substring(0, 12).toUpperCase();
            log.info("YunExpress mock-mode: envío simulado para pedido {} ({} líneas declaradas, {} céntimos USD, {})",
                    order.getOrderNumber(), parcels.size(), valuation.declaredValueCents(), valuation.taxMode());
            return new FulfillmentResult(CARRIER_NAME, "YT" + hex + "YE", "YE" + hex, etaMax);
        }
        return realCreateShipment(order, etaMax, valuation);
    }

    /**
     * Una línea de {@code Parcels[]} de {@code /api/WayBill/CreateOrder}, con los nombres de la API.
     *
     * <p>Obligatorios según la especificación: {@code EName}, {@code Quantity}, {@code UnitPrice},
     * {@code UnitWeight} y {@code CurrencyCode}. {@code HSCode}, {@code InvoicePart} (材质, material) e
     * {@code InvoiceUsage} (用途, uso) figuran como opcionales, pero son justo lo que la aduana de destino
     * usa para clasificar y liquidar: sin ellos el despacho DDP se ralentiza o se liquida de más.
     */
    public record ParcelDeclaration(String eName, String cName, String hsCode, int quantity,
                                    double unitPrice, String currencyCode, double unitWeightKg,
                                    String invoicePart, String invoiceUsage, String productUrl, String sku) {
    }

    /** Declaración aduanera del pedido: una entrada de {@code Parcels[]} por línea. */
    public List<ParcelDeclaration> declaredParcels(Order order) {
        List<ParcelDeclaration> out = new ArrayList<>();
        String currency = order.getCurrency() != null && !order.getCurrency().isBlank()
                ? order.getCurrency().toUpperCase() : "USD";
        for (OrderItem item : order.getItems()) {
            ProductEntity product = item.getProductId() != null
                    ? productRepository.findById(item.getProductId()).orElse(null) : null;
            out.add(new ParcelDeclaration(
                    englishName(item, product),
                    chineseName(item, product),
                    product != null ? product.getHsCode() : null,
                    Math.max(1, item.getQuantity()),
                    item.getUnitPriceCents() / 100.0,
                    currency,
                    unitWeightKg(product, item),
                    product != null ? product.getCustomsMaterial() : null,
                    product != null ? product.getCustomsUsage() : null,
                    item.getProductSourceUrl(),
                    item.getSkuSnapshot()));
        }
        return out;
    }

    /** Qué le falta a la declaración para pasar aduana sin fricción (vacío = completa). */
    public List<String> customsGaps(List<ParcelDeclaration> parcels) {
        List<String> gaps = new ArrayList<>();
        for (ParcelDeclaration p : parcels) {
            String who = p.sku() != null ? p.sku() : p.eName();
            if (p.eName() == null || p.eName().isBlank()) {
                gaps.add("sin nombre en inglés (EName): " + who);
            }
            // YunExpress RECHAZA la guía si el CName falta o no lleva ideogramas, así que es un hueco
            // bloqueante, no una mejora de despacho.
            if (!hasChinese(p.cName())) {
                gaps.add("sin nombre en chino (CName): " + who);
            }
            if (p.hsCode() == null || p.hsCode().isBlank()) {
                gaps.add("sin partida arancelaria (HSCode): " + who);
            }
            if (p.unitWeightKg() <= 0) {
                gaps.add("sin peso unitario (UnitWeight): " + who);
            }
            if (p.unitPrice() <= 0) {
                gaps.add("sin valor declarado (UnitPrice): " + who);
            }
        }
        return gaps;
    }

    /** Nombre declarado en inglés: traducción EN del pedido, luego la del producto, luego el título guardado. */
    private String englishName(OrderItem item, ProductEntity product) {
        if (item.getProductTitles() != null) {
            String fromOrder = item.getProductTitles().get("en");
            if (fromOrder != null && !fromOrder.isBlank()) {
                return fromOrder;
            }
        }
        if (product != null && product.getTranslations() != null) {
            Optional<String> fromProduct = product.getTranslations().stream()
                    .filter(t -> "en".equalsIgnoreCase(t.getLanguage()))
                    .map(ProductTranslationEntity::getTitle)
                    .filter(t -> t != null && !t.isBlank()).findFirst();
            if (fromProduct.isPresent()) {
                return fromProduct.get();
            }
        }
        return item.getTitleSnapshot();
    }

    /**
     * Nombre declarado en chino (CName). No es un adorno: YunExpress lo valida al crear el envío
     * ({@code 必填，且不得为纯数字或纯字母}) y rechaza la guía si falta o si no lleva ideogramas.
     *
     * <p>Por eso se exige que el texto contenga caracteres Han y se prefiere la <b>traducción {@code zh}
     * del catálogo</b> antes que la columna {@code product.title_zh}: esa columna quedó poblada con el
     * título en español en el catálogo actual, y mandarla haría fallar todos los envíos.
     */
    private String chineseName(OrderItem item, ProductEntity product) {
        if (hasChinese(item.getProductTitleZh())) {
            return item.getProductTitleZh();
        }
        if (product != null && product.getTranslations() != null) {
            Optional<String> fromTranslation = product.getTranslations().stream()
                    .filter(t -> "zh".equalsIgnoreCase(t.getLanguage()))
                    .map(ProductTranslationEntity::getTitle)
                    .filter(YunExpressFulfillmentService::hasChinese).findFirst();
            if (fromTranslation.isPresent()) {
                return fromTranslation.get();
            }
        }
        return product != null && hasChinese(product.getTitleZh()) ? product.getTitleZh() : null;
    }

    /** ¿El texto lleva algún ideograma? Es lo que YunExpress comprueba para dar por válido el CName. */
    static boolean hasChinese(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    /** Peso unitario declarado en kg: el de la variante comprada si la hay, si no el del producto. */
    private double unitWeightKg(ProductEntity product, OrderItem item) {
        if (product == null) {
            return 0.0;
        }
        if (item.getVariantId() != null && product.getVariants() != null) {
            Optional<Integer> grams = product.getVariants().stream()
                    .filter(v -> item.getVariantId().equals(v.getId()))
                    .map(v -> v.getPackageWeightGrams() != null && v.getPackageWeightGrams() > 0
                            ? v.getPackageWeightGrams() : v.getWeightGrams())
                    .filter(g -> g != null && g > 0).findFirst();
            if (grams.isPresent()) {
                return grams.get() / 1000.0;
            }
        }
        Integer productGrams = product.getPackageWeightGrams() != null && product.getPackageWeightGrams() > 0
                ? product.getPackageWeightGrams() : product.getWeightGrams();
        return productGrams != null && productGrams > 0 ? productGrams / 1000.0 : 0.0;
    }

    /**
     * Valoración aduanera del pedido: el importe que se declara es el valor INTRÍNSECO de los bienes —
     * lo que el cliente pagó por el producto (subtotal − descuento) — nunca el coste de compra al
     * proveedor. Declarar el coste haría que el transportista liquidase menos impuesto del cobrado al
     * cliente (diferencia retenida que no corresponde) y constituiría infradeclaración en aduana.
     */
    public CustomsValuation declarationFor(Order order) {
        int intrinsic = Math.max(0, order.getSubtotalCents() - order.getDiscountCents());
        return customsValuation.valuate(order.getShippingCountry(), intrinsic, order.getTaxCents());
    }

    /**
     * Creación REAL del envío: {@code POST /v1/order/package/create}.
     *
     * <p>El canal ({@code product_code}) es obligatorio: se usa el fijado en configuración y, si no lo
     * hay, el más barato que devuelva la simulación de tarifa para este bulto y destino.
     *
     * <p>El bloque aduanero sale de {@code valuation}: se declara el valor INTRÍNSECO de los bienes y el
     * IOSS solo se envía cuando el pedido NO supera el umbral de minimis del destino — por encima de
     * 150 € el régimen IOSS no aplica y mandarlo haría que la aduana rechazase la liquidación.
     */
    private FulfillmentResult realCreateShipment(Order order, int etaMax, CustomsValuation valuation) {
        ParcelSpec parcel = parcelOf(order);
        String channel = resolveProductCode(order.getShippingCountry(), parcel);
        YunExpressRequests.CreateShipment payload = createPayload(order, parcel, channel, valuation);
        JsonNode response;
        try {
            response = client.post(pathCreate(), payload);
        } catch (RuntimeException e) {
            // Red, timeout o 5xx: no sabemos si el envío llegó a crearse, así que se reintenta.
            throw FulfillmentFailure.of(e);
        }
        if (!response.path(SUCCESS).asBoolean(false)) {
            // Aquí el carrier SÍ contestó: el código dice si el problema se arregla con el tiempo o si
            // hace falta que alguien cambie el canal, el peso o la declaración.
            throw FulfillmentFailure.from("YunExpress rechazó el envío del pedido " + order.getOrderNumber()
                    + ": " + response.path("code").asText("") + " " + response.path("msg").asText(""));
        }
        JsonNode result = response.path(RESULT);
        String waybill = result.path("waybill_number").asText("");
        if (waybill.isBlank()) {
            throw new FulfillmentFailure(FulfillmentFailure.Kind.TRANSIENT,
                    "YunExpress no devolvió número de guía para el pedido " + order.getOrderNumber()
                            + ": " + result);
        }
        log.info("YunExpress: envío creado pedido={} canal={} guía={}",
                order.getOrderNumber(), channel, waybill);
        subscribeTracking(waybill);
        return new FulfillmentResult(CARRIER_NAME, trackingOf(result, waybill), waybill, etaMax);
    }

    /**
     * Suscribe la guía al push de trazabilidad para que YunExpress empuje los eventos a nuestro webhook
     * en vez de esperar al sondeo.
     *
     * <p>Un fallo aquí NO tumba la creación del envío: el paquete ya está dado de alta y el sondeo
     * periódico sigue actualizando el seguimiento, así que perder la suscripción es degradación, no error.
     */
    void subscribeTracking(String waybillNumber) {
        if (!trackingSubscriptionEnabled) {
            return;
        }
        YunExpressRequests.SubscribeTracking payload = new YunExpressRequests.SubscribeTracking(
                List.of(waybillNumber), trackingSubscribeType, List.of("Y"));
        try {
            JsonNode response = client.post(pathSubscribe(), payload);
            if (!response.path(SUCCESS).asBoolean(false)) {
                log.warn("YunExpress: no se pudo suscribir la guía {} al push -> {} {}", waybillNumber,
                        response.path("code").asText(""), response.path("msg").asText(""));
            }
        } catch (RuntimeException e) {
            log.warn("YunExpress: fallo suscribiendo la guía {} al push -> {}", waybillNumber, e.getMessage());
        }
    }

    /**
     * Número de seguimiento que se le enseña al cliente: el del tramo final si el canal ya lo ha asignado
     * y, si no, la propia guía. Al crear el envío {@code tracking_number} suele venir vacío o nulo —lo
     * asigna el transportista de destino más tarde—, así que sin este respaldo el pedido se quedaría sin
     * número que consultar.
     */
    static String trackingOf(JsonNode result, String waybill) {
        String tracking = result.path("tracking_number").asText("");
        return tracking.isBlank() ? waybill : tracking;
    }

    /** Cuerpo de {@code /v1/order/package/create} con los nombres de campo de la API. */
    YunExpressRequests.CreateShipment createPayload(Order order, ParcelSpec parcel, String channel,
            CustomsValuation valuation) {
        return createPayload(order, parcel, channel, valuation, order.getOrderNumber(),
                declarationInfoOf(order));
    }

    /**
     * Cuerpo del alta de envío. El número de cliente y la declaración se pasan aparte porque, al repartir
     * un pedido en varios bultos, cada guía lleva su propio sufijo y solo lo que viaja en ese bulto.
     */
    YunExpressRequests.CreateShipment createPayload(Order order, ParcelSpec parcel, String channel,
            CustomsValuation valuation, String customerOrderNumber,
            List<YunExpressRequests.DeclarationLine> declaration) {
        YunExpressRequests.Parcel box = new YunExpressRequests.Parcel(
                new BigDecimal(Math.max(1, parcel.weightGrams()))
                        .divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP),
                parcel.hasDimensions() ? new BigDecimal(cm(parcel.lengthMm())) : null,
                parcel.hasDimensions() ? new BigDecimal(cm(parcel.widthMm())) : null,
                parcel.hasDimensions() ? new BigDecimal(cm(parcel.heightMm())) : null);

        // El IOSS solo viaja cuando el pedido NO supera el umbral de minimis: por encima el régimen no
        // aplica y declararlo hace que la aduana rechace la liquidación.
        String ioss = iossNumberOrNull();
        YunExpressRequests.CustomsNumber customs = ioss != null && !valuation.deMinimisExceeded()
                ? new YunExpressRequests.CustomsNumber(ioss) : null;

        return new YunExpressRequests.CreateShipment(channel, customerOrderNumber, "KG", "CM", "W", labelType,
                List.of(box), receiverOf(order), declaration, customs);
    }

    /** Destinatario a partir del snapshot de dirección del pedido. */
    private YunExpressRequests.Receiver receiverOf(Order order) {
        String[] name = splitName(order.getShippingFullName());
        return new YunExpressRequests.Receiver(name[0], name[1], upper(order.getShippingCountry()),
                order.getShippingState(), order.getShippingCity(), addressLines(order),
                order.getShippingPostalCode(), order.getShippingPhone(), order.getShippingEmail());
    }

    private static List<String> addressLines(Order order) {
        List<String> lines = new ArrayList<>();
        if (order.getShippingLine1() != null && !order.getShippingLine1().isBlank()) {
            lines.add(order.getShippingLine1().trim());
        }
        if (order.getShippingLine2() != null && !order.getShippingLine2().isBlank()) {
            lines.add(order.getShippingLine2().trim());
        }
        return lines;
    }

    /**
     * Parte el nombre completo en nombre y apellidos, que es como los pide la API. Con una sola palabra
     * se repite en ambos campos: dejar el apellido vacío hace que la etiqueta salga incompleta.
     */
    static String[] splitName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return new String[] { "Customer", "Customer" };
        }
        String trimmed = fullName.trim().replaceAll("\\s+", " ");
        int cut = trimmed.indexOf(' ');
        if (cut < 0) {
            return new String[] { trimmed, trimmed };
        }
        return new String[] { trimmed.substring(0, cut), trimmed.substring(cut + 1) };
    }

    /** {@code declaration_info[]}: la declaración aduanera ya construida, con los nombres de la API. */
    private List<YunExpressRequests.DeclarationLine> declarationInfoOf(Order order) {
        List<YunExpressRequests.DeclarationLine> lines = new ArrayList<>();
        for (ParcelDeclaration parcel : declaredParcels(order)) {
            lines.add(toDeclarationLine(parcel, parcel.quantity()));
        }
        return lines;
    }

    /** Una línea de la declaración con la cantidad que realmente viaja (puede diferir al repartir bultos). */
    private static YunExpressRequests.DeclarationLine toDeclarationLine(ParcelDeclaration parcel, int quantity) {
        return new YunExpressRequests.DeclarationLine(parcel.eName(), parcel.cName(), quantity,
                BigDecimal.valueOf(parcel.unitPrice()), BigDecimal.valueOf(parcel.unitWeightKg()),
                parcel.currencyCode(), parcel.hsCode(), parcel.invoicePart(), parcel.invoiceUsage(),
                parcel.productUrl(), parcel.sku());
    }

    /** Canal a usar: el fijado en configuración o, si no hay, el más barato que cotice el destino. */
    private String resolveProductCode(String countryCode, ParcelSpec parcel) {
        if (productCode != null && !productCode.isBlank()) {
            return productCode.trim();
        }
        RateOption best = cheapestRate(countryCode, parcel, chargeableWeightGrams(parcel));
        if (best == null) {
            throw new FulfillmentFailure(FulfillmentFailure.Kind.PERMANENT,
                    "YunExpress no ofrece ningún canal para " + countryCode
                            + "; fija nexadrop.yunexpress.product-code con un canal del contrato");
        }
        return best.productCode();
    }

    /** Reconstruye el bulto del pedido con las mismas reglas que la vista previa del checkout. */
    ParcelSpec parcelOf(Order order) {
        ParcelAggregator aggregator = new ParcelAggregator();
        for (OrderItem item : order.getItems()) {
            ProductEntity product = item.getProductId() != null
                    ? productRepository.findById(item.getProductId()).orElse(null) : null;
            if (product == null) {
                aggregator.addUnknown(item.getQuantity());
                continue;
            }
            ProductVariantEntity variant = null;
            if (item.getVariantId() != null && product.getVariants() != null) {
                variant = product.getVariants().stream()
                        .filter(v -> item.getVariantId().equals(v.getId())).findFirst().orElse(null);
            }
            aggregator.add(product, variant, item.getQuantity());
        }
        return aggregator.build();
    }

    private static String upper(String value) {
        return value != null ? value.trim().toUpperCase() : null;
    }

    // ── Etiqueta y cancelación ───────────────────────────────────────────────────────────────────

    /**
     * Etiqueta del envío. Admite guía, número de cliente o tracking.
     *
     * <p>YunExpress devuelve la etiqueta de una de dos formas según el canal: {@code url} (un PDF alojado
     * por ellos, que es lo que devuelve el sandbox) o {@code label_string} con el contenido embebido en
     * Base64. Se prefiere el contenido embebido cuando viene, porque la URL caduca.
     */
    public String labelFor(String orderNumber) {
        JsonNode response = client.get(pathLabel(), Map.of("order_number", orderNumber));
        if (!response.path(SUCCESS).asBoolean(false)) {
            throw new IllegalStateException("YunExpress no devolvió etiqueta para " + orderNumber + ": "
                    + response.path("code").asText("") + " " + response.path("msg").asText(""));
        }
        JsonNode result = response.path(RESULT);
        if (result.isArray() && !result.isEmpty()) {
            result = result.get(0);
        }
        return labelOf(result);
    }

    /** Extrae la etiqueta del {@code result}: contenido embebido si lo hay, si no la URL. */
    static String labelOf(JsonNode result) {
        String embedded = result.path("label_string").asText("");
        if (!embedded.isBlank()) {
            return embedded;
        }
        String url = result.path("url").asText("");
        return url.isBlank() ? result.path("label_url").asText("") : url;
    }

    /** Anula la guía en YunExpress (solo posible antes de que el envío entre en almacén). */
    public boolean cancelShipment(String waybillNumber) {
        JsonNode response = client.post(pathCancel(), new YunExpressRequests.CancelShipment(waybillNumber));
        boolean ok = response.path(SUCCESS).asBoolean(false);
        if (!ok) {
            log.warn("YunExpress: no se pudo anular la guía {} -> {} {}", waybillNumber,
                    response.path("code").asText(""), response.path("msg").asText(""));
        }
        return ok;
    }

    // ── Tracking ─────────────────────────────────────────────────────────────────────────────────

    @Override
    public TrackingSnapshot track(String trackingNumber, Instant forwardedAt, String countryCode) {
        if (isActive()) {
            return realTrack(trackingNumber, countryCode);
        }
        if (!mockAllowed()) {
            // Sin proveedor real no hay trazabilidad que dar: se deja el envío como registrado en vez de
            // inventar un avance que acabaría marcando el pedido como entregado.
            return new TrackingSnapshot(OrderStatus.FORWARDED, List.of());
        }
        Instant start = forwardedAt != null ? forwardedAt : Instant.now();
        long elapsedMin = Math.max(0, Duration.between(start, Instant.now()).toMinutes());
        long stageDur = Math.max(1, mockStageMinutes);
        int stage = (int) Math.min(5, elapsedMin / stageDur);
        String country = countryCode != null ? countryCode : "destino";

        String[][] plan = {
                { "FORWARDED", "Envío registrado", "Shenzhen, CN" },
                { SHIPPED, "Recogido por el transportista", "Shenzhen, CN" },
                { SHIPPED, "En tránsito internacional", "Hub internacional" },
                { SHIPPED, "Llegó al país de destino", country },
                { SHIPPED, "En reparto", "Centro de distribución local" },
                { "DELIVERED", "Entregado al destinatario", country },
        };
        List<TrackingStep> steps = new ArrayList<>();
        for (int i = 0; i <= stage; i++) {
            steps.add(new TrackingStep(OrderStatus.valueOf(plan[i][0]), plan[i][1], plan[i][2],
                    start.plus(Duration.ofMinutes(i * stageDur))));
        }
        OrderStatus current = OrderStatus.valueOf(plan[stage][0]);
        return new TrackingSnapshot(current, steps);
    }

    /**
     * Tracking REAL: {@code GET /v1/track-service/info/get}. El parámetro admite guía, número de cliente
     * o tracking, así que sirve tanto el número que guardamos como la referencia de fulfillment.
     */
    private TrackingSnapshot realTrack(String trackingNumber, String countryCode) {
        JsonNode response = client.get(pathTrack(), Map.of("order_number", trackingNumber));
        if (!response.path(SUCCESS).asBoolean(false)) {
            log.warn("YunExpress: sin trazabilidad para {} -> {} {}", trackingNumber,
                    response.path("code").asText(""), response.path("msg").asText(""));
            return new TrackingSnapshot(OrderStatus.FORWARDED, List.of());
        }
        JsonNode entry = firstTrackEntry(response.path(RESULT));
        if (entry == null) {
            return new TrackingSnapshot(OrderStatus.FORWARDED, List.of());
        }
        return toSnapshot(entry.path("track_Info").path("track_events"), countryCode);
    }

    /**
     * Trazabilidad del envío consultado. YunExpress devuelve unas veces un array (una entrada por número
     * consultado) y otras el objeto suelto; como aquí siempre se pregunta por UN número, se toma la
     * primera entrada. Un array vacío es "sin trazabilidad todavía" → {@code null}.
     */
    private static JsonNode firstTrackEntry(JsonNode result) {
        if (!result.isArray()) {
            return result;
        }
        return result.isEmpty() ? null : result.get(0);
    }

    /**
     * Convierte los {@code track_events} de YunExpress en la línea temporal del pedido.
     *
     * <p>Los eventos se ordenan por fecha y el estado resultante es el del ÚLTIMO evento conocido, no el
     * más avanzado de la lista: si un paquete se devuelve después de un intento de entrega, el pedido no
     * puede quedarse en "entregado".
     */
    public TrackingSnapshot toSnapshot(JsonNode trackEvents, String countryCode) {
        List<TrackingStep> steps = new ArrayList<>();
        for (JsonNode event : trackEvents) {
            String nodeCode = event.path("track_node_code").asText("");
            YunExpressTrackNode node = YunExpressTrackNode.from(nodeCode);
            if (node == null && !nodeCode.isBlank()) {
                log.warn("YunExpress: nodo de trazabilidad desconocido '{}' — se trata como en tránsito", nodeCode);
            }
            OrderStatus status = node != null ? node.status() : OrderStatus.SHIPPED;
            String description = event.path("process_content").asText("");
            if (description.isBlank()) {
                description = node != null ? node.description() : nodeCode;
            }
            steps.add(new TrackingStep(status, description, location(event, countryCode),
                    parseInstant(event)));
        }
        steps.sort(Comparator.comparing(TrackingStep::occurredAt));
        OrderStatus current = steps.isEmpty()
                ? OrderStatus.FORWARDED : steps.get(steps.size() - 1).status();
        return new TrackingSnapshot(current, steps);
    }

    /** Ubicación legible del evento: la más específica que informe YunExpress. */
    private static String location(JsonNode event, String countryCode) {
        for (String field : new String[] { "process_location", "process_city", "process_province", "process_country" }) {
            String value = event.path(field).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return countryCode != null ? countryCode : "";
    }

    /**
     * Fecha del evento. Se prefiere {@code process_utc_time} (viene en UTC); {@code process_time} es hora
     * local del punto de escaneo y usarla desordenaría la línea temporal entre husos horarios.
     */
    private static Instant parseInstant(JsonNode event) {
        for (String field : new String[] { "process_utc_time", "process_time" }) {
            String value = event.path(field).asText("");
            if (value.isBlank()) {
                continue;
            }
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException e) {
                log.debug("YunExpress: fecha de trazabilidad no parseable '{}'", value);
            }
        }
        return Instant.now();
    }

    // ── Impuestos (DDP/DDU + IOSS) ───────────────────────────────────────────────────────────────

    /**
     * Modo de despacho fiscal (DDP/DDU) del país destino. Se resuelve por país en
     * {@code country_customs_rule}; el valor de {@code nexadrop.yunexpress.default-tax-mode} solo se usa
     * como red de seguridad para destinos sin regla configurada.
     */
    @Override
    public TaxMode taxModeFor(String countryCode) {
        TaxMode configured = customsValuation.taxModeFor(countryCode);
        return configured != null ? configured : TaxMode.from(defaultTaxMode);
    }

    /** Número IOSS configurado del comercio (o null), para despacho de IVA en la UE. */
    public String iossNumberOrNull() {
        return iossNumber != null && !iossNumber.isBlank() ? iossNumber.trim() : null;
    }
}
