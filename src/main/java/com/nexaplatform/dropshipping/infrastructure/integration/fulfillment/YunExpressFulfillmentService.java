package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexaplatform.dropshipping.application.service.CarrierChannelLimitService;
import com.nexaplatform.dropshipping.application.service.CarrierChannelLimitService.ChannelLimit;
import com.nexaplatform.dropshipping.application.service.CustomsDataCheck;
import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService;
import com.nexaplatform.dropshipping.application.service.CustomsValuationService;
import com.nexaplatform.dropshipping.application.service.CustomsValuationService.CustomsValuation;
import com.nexaplatform.dropshipping.application.service.ParcelAggregator;
import com.nexaplatform.dropshipping.application.service.ParcelSplitter;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.enums.TaxMode;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.domain.model.ShippingOption;
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
import org.springframework.context.annotation.Primary;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
// @Primary queda como seguro por si mañana hay más de un FulfillmentProvider: sin él el contexto no
// arranca por ambigüedad. Hoy solo existe YunExpress, que es quien despacha todos los pedidos; lo que
// decide de verdad qué transportista lleva cada pedido es FulfillmentRouter, que los recibe en lista.
@Primary
@RequiredArgsConstructor
public class YunExpressFulfillmentService implements FulfillmentProvider {

    /**
     * Cómo se llama este transportista dentro de la plataforma. Es lo que se guarda en el pedido al cobrar
     * y lo que decide, al despachar, a quién se le pide la guía.
     *
     * <p>Hay que declararlo aunque parezca redundante: {@code FulfillmentProvider.nombre()} trae
     * {@code DESCONOCIDO} por defecto, y con ese valor el pedido no sabría de quién es, la pantalla
     * enseñaría «Transporte estándar» en vez del nombre real y el despacho no encontraría a nadie a quien
     * pedirle la guía. El mismo texto que ya usan la migración v147 y el enum de la pantalla.
     */
    public static final String NOMBRE = "YUNEXPRESS";

    // Literales repetidos extraídos a constantes (java:S1192): una sola fuente por valor.
    private static final String SHIPPED = "SHIPPED";
    private static final String SUCCESS = "success";
    private static final String RESULT = "result";

    /** Nombre de cara al cliente (sin exponer marca del carrier), igual que hacía Cainiao. */
    private static final String CARRIER_NAME = "Standard Shipping";

    /** Etiqueta del servicio de prepago tal y como la nombra el transportista en su documentación. */
    private static final String PREPAID_VAT_LABEL = "云途预缴";

    /** Prefijo de las propiedades que permiten reapuntar una ruta de la Open Platform sin tocar código. */
    private static final String ROUTE_PROPERTY_PREFIX = "nexadrop.yunexpress.path.";

    private final CainiaoZoneRepository zoneRepository;
    private final YunExpressClient client;
    private final CustomsValuationService customsValuation;
    private final CustomsDutyLinesService customsDutyLines;
    /** Para la declaración aduanera por línea: nombre EN/ZH, partida, material, uso y peso del artículo. */
    private final ProductRepository productRepository;
    /** La tarifa de YunExpress llega en su divisa (RMB); el sistema cotiza en céntimos USD. */
    private final CurrencyRateService currencyRateService;
    /** Para saber si el entorno es productivo y, por tanto, si el modo simulado está permitido. */
    private final Environment environment;
    /**
     * Peso máximo por bulto y divisor volumétrico de cada canal en cada país. Puede llegar nulo en
     * pruebas unitarias que no tocan la tabla —como {@link #environment}—, y entonces se usan los
     * escalares de configuración, que es exactamente lo que hace el propio resolutor con un canal que no
     * está sembrado.
     */
    private final CarrierChannelLimitService channelLimits;

    @Value("${nexadrop.yunexpress.enabled:false}")
    private boolean enabled;
    /** DDP = impuestos incluidos (los paga el comercio); DDU = los paga el destinatario en destino. */
    @Value("${nexadrop.yunexpress.default-tax-mode:DDP}")
    private String defaultTaxMode;
    /** Número IOSS del comercio para despacho de IVA en la UE (pedidos <=150 EUR). Vacío = sin IOSS. */
    @Value("${nexadrop.yunexpress.ioss-number:}")
    private String iossNumber;

    /**
     * Código del servicio adicional con el que se le pide al transportista que prepague el IVA con su
     * propio IOSS ({@code V1} = 云途预缴). Vaciarlo desactiva la petición.
     */
    @Value("${nexadrop.yunexpress.prepaid-vat-service-code:V1}")
    private String prepaidVatServiceCode = "V1";
    /** Minutos por etapa del tracking simulado (mock) — pon un valor pequeño para ver el avance en demo. */
    @Value("${nexadrop.yunexpress.mock-stage-minutes:2}")
    private long mockStageMinutes;
    /**
     * Divisor del peso volumétrico <b>de reserva</b>: kg = L×W×H(cm) / divisor. 6000 es el estándar de
     * aéreo/small parcel, pero no vale para todas las líneas —la de ropa no aplica volumétrico y la de
     * carga general divide entre 8000—, así que el divisor real sale de {@link CarrierChannelLimitService}
     * y éste solo cubre los canales que no están en la tabla.
     */
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
    /**
     * Canales que el contrato permite usar, separados por comas (p. ej. {@code FZZXR,THPHR}). Vacío = no
     * se filtra, que es lo que necesita el entorno de pruebas —su canal {@code BPA} no existe en
     * producción— y cualquier cuenta sin esta restricción.
     *
     * <p>Existe porque el transportista cotiza más líneas de las que se pueden usar y cada una admite
     * una clase de mercancía: la de ropa solo textil, las económicas carga general, y las hay de
     * cosmética o de artículos con batería. Ver {@link #contractedRates}.
     */
    @Value("${nexadrop.yunexpress.allowed-product-codes:}")
    private String allowedProductCodes;
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
     * Peso máximo por bulto <b>de reserva</b>. El bueno sale de {@link CarrierChannelLimitService}, que
     * lo resuelve por (canal, país) porque el transportista lo publica así: 30 kg a España y 15 kg a
     * Dinamarca en la misma línea de ropa. Este escalar solo se usa cuando el canal no está en la tabla
     * —el {@code BPA} del entorno de pruebas, que no admite más de 2 kg— o en pruebas unitarias sin
     * resolutor. Con 0 no se reparte: todo el pedido viaja en un único envío.
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
        return environment == null ? specDefault : environment.getProperty(ROUTE_PROPERTY_PREFIX + name, specDefault);
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
    public String nombre() {
        return NOMBRE;
    }

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
        // Al cotizar todavía no hay canal elegido —se pregunta justo para saber cuáles hay—, así que se
        // pesa con el canal fijado en configuración; sin él, con la configuración global.
        int chargeableGrams = chargeableWeightGrams(parcel, defaultChannel(), zn.getCountryCode());
        if (isActive()) {
            // Todas las formas de envío utilizables, no solo la más barata: el cliente elige en el
            // checkout entre precio y plazo, que es lo que cambia de un canal a otro.
            List<ShippingOption> options = shippingOptions(zn, parcel, chargeableGrams);
            if (!options.isEmpty()) {
                // Sin elección explícita se cobra la primera, que es la más barata utilizable.
                ShippingOption cheapest = options.getFirst();
                return new ShippingQuote(true, zn.getCountryCode(), cheapest.amountUsdCents(), CARRIER_NAME,
                        CARRIER_NAME, cheapest.etaMinDays(), cheapest.etaMaxDays(), zn.getZone(), options);
            }
        }
        double kg = Math.max(0.1, chargeableGrams / 1000.0);
        int amount = zn.getBaseCents() + (int) Math.round(zn.getPerKgCents() * kg);
        return new ShippingQuote(true, zn.getCountryCode(), amount, CARRIER_NAME, CARRIER_NAME, zn.getEtaMinDays(),
                zn.getEtaMaxDays(), zn.getZone());
    }

    /**
     * Las formas de envío que se le pueden ofrecer al cliente para ese destino y bulto, de más barata a
     * más cara y ya sin las que no pueden cumplir el DDP.
     *
     * <p>El plazo sale del propio canal, no de la tabla de zonas: cada uno tarda lo suyo y enseñar el de
     * la tabla prometería una fecha que no es la del envío que se está cobrando.
     *
     * <p>Lista vacía si el transportista no cotiza nada utilizable o si falla la llamada — nunca una
     * excepción: la cotización no puede tumbar el checkout, y el llamante cae a la tabla de zonas.
     */
    List<ShippingOption> shippingOptions(CainiaoZoneEntity zone, ParcelSpec parcel, int chargeableGrams) {
        List<RateOption> rates = rateOptions(zone.getCountryCode(), parcel, chargeableGrams);
        List<ShippingOption> out = new ArrayList<>();
        for (RateOption rate : rates) {
            BigDecimal usd = currencyRateService.toUsd(rate.amount(), rate.currency());
            if (usd == null) {
                log.warn("YunExpress: no se pudo convertir {} {} a USD para {}", rate.amount(), rate.currency(),
                        zone.getCountryCode());
                continue;
            }
            int cents = usd.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValue();
            out.add(new ShippingOption(rate.productCode(), rate.productName(), cents, rate.etaMinDays(),
                    rate.etaMaxDays()));
        }
        return out;
    }

    /**
     * Peso FACTURABLE sin saber por qué canal viaja: se usa el divisor de la configuración. Queda para
     * los llamantes que de verdad no lo conocen.
     */
    int chargeableWeightGrams(ParcelSpec parcel) {
        return chargeableWeightGrams(parcel, null, null);
    }

    /**
     * Peso FACTURABLE del bulto en gramos: el mayor entre el peso real y el volumétrico
     * ({@code L×W×H cm / divisor}), que es como tarifa el transportista, y nunca por debajo del mínimo
     * facturable del destino.
     *
     * <p><b>El volumétrico no se aplica en todos los canales, y donde se aplica no siempre divide entre
     * lo mismo.</b> La línea de ropa factura el peso real en todos los países
     * («所有国家：包裹实际重量不计材积») y la de carga general divide entre 8000, no entre los 6000 del aéreo
     * estándar. Por eso el divisor sale de {@code (canal, país)}: aplicarlo donde el transportista no lo
     * cobra encarece el envío al cliente por un dato que dice lo contrario, y usar un divisor menor del
     * real deja la diferencia contra el margen cuando el carrier repesa en almacén.
     *
     * <p>El volumétrico sigue entrando solo a partir de {@link #volumetricMinCm3}: las líneas de small
     * parcel no lo aplican a bultos pequeños comprimidos en bolsa.
     *
     * @param channelCode canal del transportista; vacío = todavía no se sabe, manda la configuración
     * @param countryCode país de destino (ISO-2)
     */
    int chargeableWeightGrams(ParcelSpec parcel, String channelCode, String countryCode) {
        ChannelLimit limite = limitsFor(channelCode, countryCode);
        int real = Math.max(1, parcel.weightGrams());
        int facturable = Math.max(real, limite.minBillableGrams());
        double volumeCm3 = parcel.volumeCm3();
        if (volumeCm3 < volumetricMinCm3 || !limite.aplicaVolumetrico()) {
            return facturable;
        }
        int volumetric = (int) Math.round(volumeCm3 / limite.volumetricDivisor() * 1000.0);
        return Math.max(facturable, volumetric);
    }

    /**
     * Límites del canal en ese destino. Sin resolutor (pruebas unitarias) se responde con los escalares
     * de configuración, que es el mismo último recurso que aplica él con un canal sin sembrar.
     */
    private ChannelLimit limitsFor(String channelCode, String countryCode) {
        if (channelLimits != null) {
            return channelLimits.resolve(channelCode, countryCode);
        }
        return new ChannelLimit(channelCode, countryCode, maxParcelWeightGrams, (int) volumetricDivisor, 0, 0, 0, 0,
                false, CarrierChannelLimitService.Origen.GLOBAL);
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
    record RateOption(String productCode, String productName, BigDecimal amount, String currency, int etaMinDays,
            int etaMaxDays) {
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
            log.warn("YunExpress: no se pudo convertir {} {} a USD para {}", best.amount(), best.currency(),
                    zone.getCountryCode());
            return null;
        }
        return usd.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    /**
     * Simula la tarifa y devuelve el canal más barato. Devuelve {@code null} —sin propagar el fallo— si
     * YunExpress no recomienda ninguno: la cotización nunca debe tumbar el checkout.
     */
    RateOption cheapestRate(String countryCode, ParcelSpec parcel, int chargeableGrams) {
        List<RateOption> options = rateOptions(countryCode, parcel, chargeableGrams);
        return options.isEmpty() ? null : options.getFirst();
    }

    /**
     * Todas las tarifas utilizables del destino, de más barata a más cara. Devuelve lista vacía —nunca
     * excepción— si el transportista no recomienda ninguna o la llamada falla: la cotización no puede
     * tumbar el checkout.
     */
    List<RateOption> rateOptions(String countryCode, ParcelSpec parcel, int chargeableGrams) {
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
                return List.of();
            }
            // Solo entre los canales que pueden cumplir lo que se le prometió al cliente: donde el
            // transportista prepaga el IVA, los postales y los de Amazon quedan fuera aunque sean más
            // baratos. Si no queda ninguno, se devuelve null y el llamante cae a la tabla de zonas.
            // Dos filtros, en este orden: primero fuera los que no pueden cumplir lo prometido al cliente
            // (postales y Amazon donde el transportista prepaga el IVA), y después los que el contrato
            // no permite usar. Si no queda ninguno, el llamante cae a la tabla de zonas.
            return contractedRates(deliverableRates(parseRates(response.path(RESULT)),
                    customsValuation.carrierPrepaysVatFor(countryCode)), contractedChannels());
        } catch (RuntimeException e) {
            log.warn("YunExpress: fallo simulando tarifa para {} -> {}", countryCode, e.getMessage());
            return List.of();
        }
    }

    /**
     * Canales de correo postal. Son <b>DAP y no admiten declaración IOSS</b>
     * («邮局渠道为DAP模式（税费由收件人支付），暂不支持IOSS申报»), así que con ellos el IVA se lo
     * reclaman al destinatario aunque ya lo haya pagado en la tienda.
     */
    private static final Set<String> POSTAL_CHANNELS = Set.of("CNDWA", "EUB-SZ", "SZEMS", "SNETK");

    /** Sufijo de los canales que solo aceptan el IOSS de Amazon («该产品只支持使用亚马逊平台IOSS号下单»). */
    private static final String AMAZON_ONLY_SUFFIX = "-AMZ";

    /**
     * Canales que se le pueden ofrecer al cliente, del más barato al más caro.
     *
     * <p>Cuando el destino lleva el <b>IVA prepagado</b> por el transportista hay que descartar los que
     * no pueden cumplir esa promesa: los postales, que van DAP, y los de Amazon, que exigen un IOSS que
     * no es el nuestro. Y son de los más baratos, así que quedarse con «el más barato que cotice» los
     * elige a menudo —en Alemania, Italia, México, Brasil, Colombia y Argentina, medido contra la API—.
     *
     * <p>Fuera de ese régimen no se descarta nada: allí el impuesto lo paga el destinatario con cualquier
     * canal, y quitar los baratos solo encarecería el envío sin ganar nada a cambio.
     */
    static List<RateOption> deliverableRates(List<RateOption> rates, boolean prepaidVat) {
        if (rates == null || rates.isEmpty()) {
            return List.of();
        }
        return rates.stream().filter(r -> !prepaidVat || isCompatibleWithPrepaidVat(r.productCode()))
                .sorted(Comparator.comparing(RateOption::amount)).toList();
    }

    /**
     * Deja solo los canales que el contrato permite usar, conservando el orden por precio.
     *
     * <p>El transportista cotiza para la cuenta más líneas de las que se pueden usar, y cada una admite
     * una clase de mercancía distinta: la de ropa ({@code FZZXR}) solo textil en bolsa, las de carga
     * general ({@code THPHR}) mercancía normal sin batería, y las hay de cosmética, de artículos con
     * batería o de gran volumen. Ofrecer en el checkout un canal que luego no acepta lo que va dentro
     * es cobrar un envío y descubrir en el almacén que no se puede despachar.
     *
     * <p>La lista es CONFIGURACIÓN, no código: al ampliar el contrato se añade el canal en una variable
     * de entorno, sin desplegar. <b>Vacía = no se filtra nada</b>, que es lo que necesitan el entorno de
     * pruebas —cuyo canal {@code BPA} no existe en producción— y cualquier cuenta sin esta restricción.
     *
     * <p>La comparación es exacta: {@code FZZXR-AMZ} es la misma línea por la red de Amazon y exige SU
     * número de IOSS, así que parecerse no basta para poder usarla.
     */
    /** Los canales contratados, leídos de la configuración. Conjunto vacío = sin restricción. */
    private Set<String> contractedChannels() {
        if (allowedProductCodes == null || allowedProductCodes.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(allowedProductCodes.split(",")).map(String::trim).filter(c -> !c.isEmpty())
                .map(c -> c.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    static List<RateOption> contractedRates(List<RateOption> rates, Set<String> allowed) {
        if (rates == null || rates.isEmpty()) {
            return List.of();
        }
        if (allowed == null || allowed.isEmpty()) {
            return rates;
        }
        Set<String> normalizados = allowed.stream().filter(c -> c != null && !c.isBlank())
                .map(c -> c.trim().toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        if (normalizados.isEmpty()) {
            return rates;
        }
        return rates.stream().filter(
                r -> r.productCode() != null && normalizados.contains(r.productCode().trim().toUpperCase(Locale.ROOT)))
                .toList();
    }

    private static boolean isCompatibleWithPrepaidVat(String productCode) {
        if (productCode == null || productCode.isBlank()) {
            return false;
        }
        String code = productCode.trim().toUpperCase(Locale.ROOT);
        return !POSTAL_CHANNELS.contains(code) && !code.endsWith(AMAZON_ONLY_SUFFIX);
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
                    ? item.path("convert_amount").decimalValue()
                    : item.path("calculate_amount").decimalValue();
            String currency = item.path("convert_currency").asText(null);
            if (currency == null || currency.isBlank()) {
                currency = item.path("currency").asText("CNY");
            }
            int[] eta = parseEtaDays(item.path("interval_day").asText(""));
            RateOption previous = byProduct.get(code);
            if (previous == null) {
                byProduct.put(code, new RateOption(code, item.path("product_name").asText(code), amount,
                        normalizeCurrency(currency), eta[0], eta[1]));
            } else {
                byProduct.put(code,
                        new RateOption(previous.productCode(), previous.productName(), previous.amount().add(amount),
                                previous.currency(), previous.etaMinDays(), previous.etaMaxDays()));
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
            return new int[]{0, 0};
        }
        String[] parts = intervalDay.trim().split("-");
        try {
            int min = Integer.parseInt(parts[0].trim());
            int max = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : min;
            return new int[]{min, max};
        } catch (NumberFormatException e) {
            return new int[]{0, 0};
        }
    }

    /** Milímetros a centímetros con un decimal, que es la unidad que pide la API ({@code size_unit=CM}). */
    private static String cm(int millimeters) {
        return BigDecimal.valueOf(millimeters).divide(BigDecimal.valueOf(10), 1, RoundingMode.HALF_UP).toPlainString();
    }

    /** Canales contratados (código → nombre), tal cual los publica la cuenta. Para diagnóstico y admin. */
    public List<SupportedCountry> logisticsProducts() {
        JsonNode response = client.get(pathProducts(), Map.of());
        List<SupportedCountry> out = new ArrayList<>();
        JsonNode list = response.has("detail") ? response.path("detail") : response.path(RESULT).path("list");
        for (JsonNode item : list) {
            out.add(new SupportedCountry(item.path("product_code").asText(""), item.path("product_name").asText("")));
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
        // Lo PRIMERO, antes de repartir bultos y antes de llamar a nadie: una declaración incompleta no
        // sale. Antes solo se dejaba un aviso en el registro y la guía se transmitía igual.
        requireCompleteCustoms(order);
        List<ParcelSplitter.Bin> bins = splitOrder(order);
        if (bins.size() > 1) {
            log.info("YunExpress: pedido {} repartido en {} bultos por los límites del canal", order.getOrderNumber(),
                    bins.size());
        }
        // También con un solo bulto se pasa por createShipmentForBin: así el envío guarda su peso y su
        // valor declarado. Delegar en createShipment() los dejaba a cero y el dato se perdía.
        List<FulfillmentResult> results = new ArrayList<>();
        for (int i = 0; i < bins.size(); i++) {
            try {
                results.add(createShipmentForBin(order, bins.get(i), i + 1));
            } catch (RuntimeException e) {
                // Las guías de los bultos anteriores YA existen y están pagadas en el transportista. Antes
                // esta excepción se las llevaba por delante: no se persistía ninguna, así que no aparecían
                // ni en el pedido, ni en order_shipment, ni en el registro, y el panel no podía anularlas.
                log.error("YunExpress: el bulto {} del pedido {} falló con {} guía(s) ya emitida(s): {}", i + 1,
                        order.getOrderNumber(), results.size(), e.getMessage());
                throw new EnvioParcialException(results, e);
            }
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
                    ? productRepository.findById(item.getProductId()).orElse(null)
                    : null;
            ProductVariantEntity variant = variantOf(product, item);
            int unitWeight = product != null ? ParcelAggregator.unitWeightGrams(product, variant) : 500;
            boolean battery = product != null && ParcelAggregator.hasBattery(product);
            for (int q = 0; q < Math.max(1, item.getQuantity()); q++) {
                units.add(new ParcelSplitter.Unit(line, unitWeight, item.getUnitPriceCents(),
                        dimension(product, variant, Dimension.LENGTH), dimension(product, variant, Dimension.WIDTH),
                        dimension(product, variant, Dimension.HEIGHT), battery));
            }
        }
        // El peso máximo NO es el escalar de configuración: lo fija el canal por el que va a salir la
        // guía y el país al que va. Los topes de valor y de unidades siguen siendo de configuración
        // porque el transportista no los publica por país.
        ChannelLimit limite = limitsFor(limitChannelOf(order), order.getShippingCountry());
        return ParcelSplitter.split(units,
                new ParcelSplitter.Limits(limite.maxWeightGrams(), maxParcelValueCents, maxParcelUnits));
    }

    /**
     * Canal con el que se resuelven los límites del reparto.
     *
     * <p>Manda el que <b>eligió el cliente</b> y que se va a usar para emitir la guía. Si el pedido aún
     * no lo lleva —entre la cotización y el despacho hay pasos donde todavía no está decidido— se usa el
     * canal fijado en configuración, que es por el que saldría el envío. Y si tampoco lo hay, se devuelve
     * vacío y la resolución cae a la configuración global: NO se llama aquí a la simulación de tarifa
     * para averiguarlo, porque repartir bultos no puede depender de que el transportista conteste.
     */
    private String limitChannelOf(Order order) {
        String chosen = order.getShippingChannelCode();
        if (chosen != null && !chosen.isBlank()) {
            return chosen.trim();
        }
        return defaultChannel();
    }

    /** Qué medida del paquete se está pidiendo; el producto manda sobre la variante. */
    private enum Dimension {
        LENGTH, WIDTH, HEIGHT
    }

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
        return product.getVariants().stream().filter(v -> item.getVariantId().equals(v.getId())).findFirst()
                .orElse(null);
    }

    /** Crea la guía de UN bulto concreto del pedido. */
    private FulfillmentResult createShipmentForBin(Order order, ParcelSplitter.Bin bin, int sequenceNo) {
        int etaMax = zone(order.getShippingCountry()).map(CainiaoZoneEntity::getEtaMaxDays).orElse(20);
        CustomsValuation valuation = declarationFor(order);
        // Destinatario y líneas se arman UNA vez y sirven para dos cosas: mandarlos y dejar constancia de
        // lo mandado. Construirlos dos veces abriría la puerta a que lo archivado no fuese lo transmitido,
        // que es justo lo que este registro viene a evitar.
        YunExpressRequests.Receiver receiver = receiverOf(order);
        List<YunExpressRequests.DeclarationLine> lines = declarationInfoOfBin(order, bin);
        FulfillmentProvider.ShipmentDeclaration declared = declaredCopy(receiver, lines);
        if (!isActive()) {
            if (!mockAllowed()) {
                throw new FulfillmentFailure(FulfillmentFailure.Kind.TRANSIENT,
                        "YunExpress no está operativo y en producción no se generan envíos simulados");
            }
            // El simulado también archiva la declaración: es la única forma de probar la ficha del pedido
            // en desarrollo, y el mock está prohibido en pre/pro, así que no puede confundir a nadie.
            String hex = order.getId().toString().replace("-", "").substring(0, 10).toUpperCase() + sequenceNo;
            return new FulfillmentResult(CARRIER_NAME, "YT" + hex + "YE", "YE" + hex, etaMax, sequenceNo,
                    bin.spec().weightGrams(), bin.valueCents(), productCode, contentsOf(bin), declared);
        }
        String channel = channelFor(order, bin.spec());
        // El número de cliente debe ser único por guía: el mismo para dos envíos lo rechaza el carrier.
        YunExpressRequests.CreateShipment payload = createPayload(order, bin.spec(), channel, valuation,
                order.getOrderNumber() + "-" + sequenceNo, lines, receiver);
        JsonNode response;
        try {
            response = client.post(pathCreate(), payload);
        } catch (RuntimeException e) {
            throw FulfillmentFailure.of(e);
        }
        if (!response.path(SUCCESS).asBoolean(false)) {
            throw FulfillmentFailure
                    .from("YunExpress rechazó el bulto " + sequenceNo + " del pedido " + order.getOrderNumber() + ": "
                            + response.path("code").asText("") + " " + response.path("msg").asText(""));
        }
        JsonNode result = response.path(RESULT);
        String waybill = result.path("waybill_number").asText("");
        if (waybill.isBlank()) {
            throw new FulfillmentFailure(FulfillmentFailure.Kind.TRANSIENT, "YunExpress no devolvió guía para el bulto "
                    + sequenceNo + " del pedido " + order.getOrderNumber());
        }
        subscribeTracking(waybill);
        return new FulfillmentResult(CARRIER_NAME, trackingOf(result, waybill), waybill, etaMax, sequenceNo,
                bin.spec().weightGrams(), bin.valueCents(), channel, contentsOf(bin), declared);
    }

    /**
     * Copia de lo que se transmite al transportista, en los tipos del puerto.
     *
     * <p>No se archiva el cuerpo de YunExpress tal cual porque lo que el administrador tiene que poder
     * comprobar es la DECLARACIÓN, no el formato de un transportista concreto: el día que se cambie de
     * carrier, lo ya archivado tiene que seguir leyéndose igual. Y por lo mismo se copia campo a campo y
     * no se serializa la petición entera: así no hay forma de que un dato de la llamada (credenciales,
     * firma) acabe en la base de datos por descuido.
     */
    static FulfillmentProvider.ShipmentDeclaration declaredCopy(YunExpressRequests.Receiver receiver,
            List<YunExpressRequests.DeclarationLine> lines) {
        List<FulfillmentProvider.DeclaredLine> declared = new ArrayList<>();
        for (YunExpressRequests.DeclarationLine line : lines) {
            declared.add(new FulfillmentProvider.DeclaredLine(line.nameEn(), line.nameLocal(), line.hsCode(),
                    line.quantity(), line.unitPrice(), line.currency(), line.unitWeight(), line.material(),
                    line.purpose(), line.skuCode(), line.salesUrl()));
        }
        FulfillmentProvider.DeclaredReceiver to = new FulfillmentProvider.DeclaredReceiver(receiver.firstName(),
                receiver.lastName(), receiver.countryCode(), receiver.province(), receiver.city(),
                receiver.addressLines(), receiver.postalCode(), receiver.phoneNumber(), receiver.email());
        return new FulfillmentProvider.ShipmentDeclaration(to, declared);
    }

    /**
     * Qué líneas del pedido —y cuántas unidades de cada una— viajan en este bulto.
     *
     * <p>El reparto ya está hecho: cada unidad colocada recuerda de qué línea salió. Se agrupa aquí para
     * que el seguimiento pueda enseñar las fotos de lo que lleva cada paquete, en lugar de un «Paquete
     * 1/2» a ciegas.
     */
    private static List<FulfillmentProvider.ParcelContent> contentsOf(ParcelSplitter.Bin bin) {
        Map<Integer, Integer> porLinea = new LinkedHashMap<>();
        for (ParcelSplitter.Unit unit : bin.units()) {
            porLinea.merge(unit.lineIndex(), 1, Integer::sum);
        }
        List<FulfillmentProvider.ParcelContent> out = new ArrayList<>();
        porLinea.forEach((linea, cantidad) -> out.add(new FulfillmentProvider.ParcelContent(linea, cantidad)));
        return out;
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

    /**
     * Corta el envío si a la declaración le falta cualquiera de los datos obligatorios.
     *
     * <p>Antes esto solo escribía un aviso en el registro y el envío se transmitía igual, con la
     * declaración coja: el transportista podía rechazar la guía —y a esas alturas el pedido ya está
     * cobrado— o la aduana retener el paquete. Nadie leía ese aviso hasta que reclamaba el cliente.
     *
     * <p>El fallo se marca PERMANENTE porque reintentarlo no va a hacer aparecer una partida arancelaria:
     * así el pedido cae de inmediato en la bandeja de incidencias del panel —con correo y aviso al
     * administrador— en lugar de gastar tres intentos en silencio. Corregido el producto, el botón de
     * relanzar del panel vuelve a intentarlo, de modo que el pedido cobrado nunca se queda sin salida.
     */
    void requireCompleteCustoms(Order order) {
        List<String> gaps = customsGaps(declaredParcels(order));
        if (gaps.isEmpty()) {
            return;
        }
        throw new FulfillmentFailure(FulfillmentFailure.Kind.PERMANENT,
                "El pedido " + order.getOrderNumber() + " no se puede declarar en aduana porque faltan datos"
                        + " obligatorios del catálogo: " + String.join("; ", gaps)
                        + ". Corrige el producto y relanza el envío.");
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
        requireCompleteCustoms(order);
        if (!isActive()) {
            if (!mockAllowed()) {
                throw new FulfillmentFailure(FulfillmentFailure.Kind.TRANSIENT,
                        "YunExpress no está operativo (enabled=" + enabled + ", credenciales=" + client.hasCredentials()
                                + ") y en producción no se generan envíos simulados");
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
    public record ParcelDeclaration(String eName, String cName, String hsCode, int quantity, double unitPrice,
            String currencyCode, double unitWeightKg, String invoicePart, String invoiceUsage, String productUrl,
            String sku) {
    }

    /**
     * Declaración aduanera del pedido: una entrada de {@code Parcels[]} <b>por línea de declaración</b>,
     * no por artículo del pedido.
     *
     * <p>El derecho de 3 EUR se cobra por línea, y lo que separa una línea de otra es la terna
     * clasificación + descripción + origen. Emitir una entrada por artículo hacía que dos productos que
     * el checkout contó como UNA línea —porque comparten grupo aprobado y viajan con la misma
     * descripción— llegaran a la aduana como DOS: el segundo derecho lo acababa poniendo el comercio al
     * despachar. Aquí se fusiona con la misma clave con la que cuenta
     * {@code CustomsDutyLinesService}, para que lo cobrado y lo declarado coincidan.
     *
     * <p>Al fusionar, la <b>cantidad se suma</b> y el <b>unitario se promedia</b> — una línea de la API
     * lleva un solo {@code UnitPrice}—. Lo que NO cambia es el valor declarado total: sobre él se miden
     * el umbral de 150 EUR del régimen y la base del IVA. Repartirlo distinto entre líneas está bien;
     * alterar la suma, no.
     */
    public List<ParcelDeclaration> declaredParcels(Order order) {
        String currency = order.getCurrency() != null && !order.getCurrency().isBlank()
                ? order.getCurrency().toUpperCase()
                : "USD";
        Map<String, DeclarationAccumulator> porLinea = new LinkedHashMap<>();
        for (OrderItem item : order.getItems()) {
            ProductEntity product = item.getProductId() != null
                    ? productRepository.findById(item.getProductId()).orElse(null)
                    : null;
            String eName = englishName(item, product);
            String hs = product != null ? product.getHsCode() : null;
            String origen = product != null ? product.getCountryOfOrigin() : null;
            int cantidad = Math.max(1, item.getQuantity());
            porLinea.computeIfAbsent(
                    CustomsDutyLinesService.claveDeLineaDeDeclaracion(hs, eName, origen, item.getProductId()),
                    clave -> new DeclarationAccumulator(
                            new ParcelDeclaration(eName, chineseName(item, product), hs, 0, 0.0, currency,
                                    unitWeightKg(product, item), product != null ? product.getCustomsMaterial() : null,
                                    product != null ? product.getCustomsUsage() : null, item.getProductSourceUrl(),
                                    item.getSkuSnapshot())))
                    .add(cantidad, (long) item.getUnitPriceCents() * cantidad);
        }
        List<ParcelDeclaration> out = new ArrayList<>();
        for (DeclarationAccumulator acumulado : porLinea.values()) {
            out.add(acumulado.toDeclaration());
        }
        return out;
    }

    /**
     * Va sumando lo que cae en una misma línea de declaración.
     *
     * <p>Guarda el valor en céntimos y solo divide al final: promediar unitarios sobre la marcha
     * arrastraría el redondeo de cada paso al valor declarado, que es justo lo que no puede moverse.
     */
    private static final class DeclarationAccumulator {

        private final ParcelDeclaration plantilla;
        private int cantidad;
        private long valorCents;

        private DeclarationAccumulator(ParcelDeclaration plantilla) {
            this.plantilla = plantilla;
        }

        private DeclarationAccumulator add(int cantidad, long valorCents) {
            this.cantidad += cantidad;
            this.valorCents += valorCents;
            return this;
        }

        private ParcelDeclaration toDeclaration() {
            double unitario = cantidad == 0 ? 0.0 : valorCents / 100.0 / cantidad;
            return new ParcelDeclaration(plantilla.eName(), plantilla.cName(), plantilla.hsCode(), cantidad, unitario,
                    plantilla.currencyCode(), plantilla.unitWeightKg(), plantilla.invoicePart(),
                    plantilla.invoiceUsage(), plantilla.productUrl(), plantilla.sku());
        }
    }

    /**
     * Qué le falta a la declaración para pasar aduana sin fricción (vacío = completa).
     *
     * <p>Los cinco datos obligatorios los decide {@link CustomsDataCheck} y no este servicio: son los
     * mismos que el panel exige para dar un producto por listo para vender, y tenerlos escritos dos veces
     * era pedir que un día dejaran de coincidir. Aquí solo se les pone nombre y dueño para el mensaje.
     */
    public List<String> customsGaps(List<ParcelDeclaration> parcels) {
        List<String> gaps = new ArrayList<>();
        for (ParcelDeclaration p : parcels) {
            String who = p.sku() != null ? p.sku() : p.eName();
            for (CustomsDataCheck.CustomsField campo : CustomsDataCheck.faltantesEnLinea(p.eName(), p.cName(),
                    p.hsCode(), p.unitWeightKg(), p.unitPrice())) {
                gaps.add("sin " + campo.etiqueta() + ": " + who);
            }
        }
        return gaps;
    }

    /** Nombre declarado en inglés: traducción EN del pedido, luego la del producto, luego el título guardado. */
    private String englishName(OrderItem item, ProductEntity product) {
        // El snapshot manda: es la descripción con la que el checkout CONTÓ los derechos. Si aquí se
        // transmitiera otra, la aduana armaría otras líneas y el número de derechos dejaría de cuadrar
        // con lo que se le cobró al cliente.
        if (item.getDeclaredDescription() != null && !item.getDeclaredDescription().isBlank()) {
            return item.getDeclaredDescription();
        }
        if (item.getProductTitles() != null) {
            String fromOrder = item.getProductTitles().get("en");
            if (fromOrder != null && !fromOrder.isBlank()) {
                return fromOrder;
            }
        }
        if (product != null && product.getTranslations() != null) {
            Optional<String> fromProduct = product.getTranslations().stream()
                    .filter(t -> "en".equalsIgnoreCase(t.getLanguage())).map(ProductTranslationEntity::getTitle)
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
        // El snapshot manda, igual que en inglés: es el CName con el que se congeló la línea. Resolverlo
        // aquí del producto dejaría la línea fusionada con el genérico aprobado en inglés y el título
        // concreto del primer artículo en chino, es decir, dos mercancías distintas en la misma línea.
        if (hasChinese(item.getDeclaredDescriptionZh())) {
            return item.getDeclaredDescriptionZh();
        }
        if (hasChinese(item.getProductTitleZh())) {
            return item.getProductTitleZh();
        }
        if (product != null && product.getTranslations() != null) {
            Optional<String> fromTranslation = product.getTranslations().stream()
                    .filter(t -> "zh".equalsIgnoreCase(t.getLanguage())).map(ProductTranslationEntity::getTitle)
                    .filter(YunExpressFulfillmentService::hasChinese).findFirst();
            if (fromTranslation.isPresent()) {
                return fromTranslation.get();
            }
        }
        return product != null && hasChinese(product.getTitleZh()) ? product.getTitleZh() : null;
    }

    /**
     * ¿El texto lleva algún ideograma? Es lo que YunExpress comprueba para dar por válido el CName.
     *
     * <p>Delega en {@link CustomsDataCheck} para que el catálogo y el despacho apliquen literalmente el
     * mismo criterio: un título que el panel dé por chino tiene que serlo también al declarar.
     */
    static boolean hasChinese(String text) {
        return CustomsDataCheck.tieneIdeogramas(text);
    }

    /** Peso unitario declarado en kg: el de la variante comprada si la hay, si no el del producto. */
    private double unitWeightKg(ProductEntity product, OrderItem item) {
        if (product == null) {
            return 0.0;
        }
        if (item.getVariantId() != null && product.getVariants() != null) {
            Optional<Integer> grams = product.getVariants().stream().filter(v -> item.getVariantId().equals(v.getId()))
                    .map(v -> v.getPackageWeightGrams() != null && v.getPackageWeightGrams() > 0
                            ? v.getPackageWeightGrams()
                            : v.getWeightGrams())
                    .filter(g -> g != null && g > 0).findFirst();
            if (grams.isPresent()) {
                return grams.get() / 1000.0;
            }
        }
        Integer productGrams = product.getPackageWeightGrams() != null && product.getPackageWeightGrams() > 0
                ? product.getPackageWeightGrams()
                : product.getWeightGrams();
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
        // El derecho fijo va por línea de declaración (partida arancelaria) y por bulto — los MISMOS bultos
        // que se van a despachar, así que se reutiliza el reparto real del transportista.
        return customsValuation.valuate(order.getShippingCountry(), intrinsic, order.getTaxCents(),
                dutyParcelsOf(order));
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
        String channel = channelFor(order, parcel);
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
            throw FulfillmentFailure.from("YunExpress rechazó el envío del pedido " + order.getOrderNumber() + ": "
                    + response.path("code").asText("") + " " + response.path("msg").asText(""));
        }
        JsonNode result = response.path(RESULT);
        String waybill = result.path("waybill_number").asText("");
        if (waybill.isBlank()) {
            throw new FulfillmentFailure(FulfillmentFailure.Kind.TRANSIENT,
                    "YunExpress no devolvió número de guía para el pedido " + order.getOrderNumber() + ": " + result);
        }
        log.info("YunExpress: envío creado pedido={} canal={} guía={}", order.getOrderNumber(), channel, waybill);
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
        YunExpressRequests.SubscribeTracking payload = new YunExpressRequests.SubscribeTracking(List.of(waybillNumber),
                trackingSubscribeType, List.of("Y"));
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
        return createPayload(order, parcel, channel, valuation, order.getOrderNumber(), declarationInfoOf(order),
                receiverOf(order));
    }

    /**
     * Cuerpo del alta de envío. El número de cliente, la declaración y el destinatario se pasan aparte
     * porque, al repartir un pedido en varios bultos, cada guía lleva su propio sufijo y solo lo que
     * viaja en ese bulto — y porque de esos mismos objetos se saca la copia que queda archivada.
     */
    YunExpressRequests.CreateShipment createPayload(Order order, ParcelSpec parcel, String channel,
            CustomsValuation valuation, String customerOrderNumber,
            List<YunExpressRequests.DeclarationLine> declaration, YunExpressRequests.Receiver receiver) {
        YunExpressRequests.Parcel box = new YunExpressRequests.Parcel(
                new BigDecimal(Math.max(1, parcel.weightGrams())).divide(BigDecimal.valueOf(1000), 3,
                        RoundingMode.HALF_UP),
                parcel.hasDimensions() ? new BigDecimal(cm(parcel.lengthMm())) : null,
                parcel.hasDimensions() ? new BigDecimal(cm(parcel.widthMm())) : null,
                parcel.hasDimensions() ? new BigDecimal(cm(parcel.heightMm())) : null);

        // El IOSS solo viaja cuando el pedido NO supera el umbral de minimis: por encima el régimen no
        // aplica y declararlo hace que la aduana rechace la liquidación.
        String ioss = iossNumberOrNull();
        YunExpressRequests.CustomsNumber customs = ioss != null && !valuation.deMinimisExceeded()
                ? new YunExpressRequests.CustomsNumber(ioss)
                : null;

        return new YunExpressRequests.CreateShipment(channel, customerOrderNumber, "KG", "CM", "W", labelType,
                List.of(box), receiver, declaration, customs, prepaidVatServices(valuation));
    }

    /**
     * Servicios adicionales del envío. Hoy solo el prepago del IVA por el transportista.
     *
     * <p>Se pide cuando el destino tiene marcado que <b>el transportista liquida su IVA</b> —hoy los 27
     * de la UE vía IOSS—, el pedido va <b>DDP</b> —el impuesto ya se le cobró al cliente en el checkout—
     * y <b>no supera el umbral</b> de minimis, que es donde el régimen simplificado aplica. En DDU lo
     * paga el destinatario, así que pedirlo lo cobraría dos veces; por encima del umbral el despacho es
     * formal y el prepago no tiene dónde liquidarse. Y sin la marca del país se pediría para destinos sin
     * ese régimen —todos están en DDP—, lo que tumbaría el alta del envío.
     *
     * <p>El código es configurable y vaciarlo desactiva el servicio: si la cuenta del transportista no lo
     * tiene dado de alta, mandarlo hace fallar la creación del envío, y eso debe poder apagarse sin
     * desplegar.
     */
    private List<YunExpressRequests.ExtraService> prepaidVatServices(CustomsValuation valuation) {
        // EL CÓDIGO SALE DEL PAÍS, no de la configuración. `V1` está definido por el transportista como
        // «prepago del IOSS de la reforma fiscal de la UE» (云途预缴IOSS附加服务费), así que mandarlo a un
        // destino de fuera hace fallar el alta del envío y deja el pedido cobrado y sin guía.
        //
        // Y hay destinos donde el transportista SÍ prepaga y NO hay que pedirle nada, porque el canal ya
        // va DDP por contrato: Emiratos, Arabia Saudí, Canadá y México con nuestras líneas FZZXR y THPHR
        // (v149). Ahí la marca está puesta y el código es nulo, y eso es exactamente lo que significa.
        String servicioDelPais = valuation == null ? null : valuation.vatPrepayServiceCode();
        boolean aplica = servicioDelPais != null && !servicioDelPais.isBlank() && prepaidVatServiceCode != null
                && !prepaidVatServiceCode.isBlank() && valuation.carrierPrepaysVat()
                && valuation.taxMode() == TaxMode.DDP && !valuation.deMinimisExceeded();
        // null y no lista vacía: así el campo desaparece del JSON en vez de viajar como `[]`, que algunas
        // validaciones del transportista rechazan.
        return aplica ? List.of(new YunExpressRequests.ExtraService(servicioDelPais.trim(), PREPAID_VAT_LABEL)) : null;
    }

    /** Destinatario a partir del snapshot de dirección del pedido. */
    private YunExpressRequests.Receiver receiverOf(Order order) {
        String[] name = splitName(order.getShippingFullName());
        return new YunExpressRequests.Receiver(name[0], name[1], upper(order.getShippingCountry()),
                order.getShippingState(), order.getShippingCity(), addressLines(order), order.getShippingPostalCode(),
                order.getShippingPhone(), order.getShippingEmail());
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
            return new String[]{"Customer", "Customer"};
        }
        String trimmed = fullName.trim().replaceAll("\\s+", " ");
        int cut = trimmed.indexOf(' ');
        if (cut < 0) {
            return new String[]{trimmed, trimmed};
        }
        return new String[]{trimmed.substring(0, cut), trimmed.substring(cut + 1)};
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
    /**
     * Canal por el que se emite la guía. Manda lo que <b>eligió el cliente</b>: entre el pedido y el
     * despacho la tarifa cambia, así que recalcular «el más barato» ahora significaría cobrar una forma
     * de envío y usar otra, con otro plazo. Si no eligió, se usa el fijado en configuración y, en su
     * defecto, el más barato utilizable del momento.
     */
    String channelFor(Order order, ParcelSpec parcel) {
        String chosen = order.getShippingChannelCode();
        if (chosen != null && !chosen.isBlank()) {
            return chosen.trim();
        }
        return resolveProductCode(order.getShippingCountry(), parcel);
    }

    /** Canal fijado en configuración, o vacío si se deja elegir al transportista. */
    private String defaultChannel() {
        return productCode != null ? productCode.trim() : "";
    }

    private String resolveProductCode(String countryCode, ParcelSpec parcel) {
        if (productCode != null && !productCode.isBlank()) {
            return productCode.trim();
        }
        RateOption best = cheapestRate(countryCode, parcel,
                chargeableWeightGrams(parcel, defaultChannel(), countryCode));
        if (best == null) {
            throw new FulfillmentFailure(FulfillmentFailure.Kind.PERMANENT, "YunExpress no ofrece ningún canal para "
                    + countryCode + "; fija nexadrop.yunexpress.product-code con un canal del contrato");
        }
        return best.productCode();
    }

    /** Reconstruye el bulto del pedido con las mismas reglas que la vista previa del checkout. */
    ParcelSpec parcelOf(Order order) {
        ParcelAggregator aggregator = new ParcelAggregator();
        for (OrderItem item : order.getItems()) {
            ProductEntity product = item.getProductId() != null
                    ? productRepository.findById(item.getProductId()).orElse(null)
                    : null;
            if (product == null) {
                aggregator.addUnknown(item.getQuantity());
                continue;
            }
            ProductVariantEntity variant = null;
            if (item.getVariantId() != null && product.getVariants() != null) {
                variant = product.getVariants().stream().filter(v -> item.getVariantId().equals(v.getId())).findFirst()
                        .orElse(null);
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

        String[][] plan = {{"FORWARDED", "Envío registrado", "Shenzhen, CN"},
                {SHIPPED, "Recogido por el transportista", "Shenzhen, CN"},
                {SHIPPED, "En tránsito internacional", "Hub internacional"},
                {SHIPPED, "Llegó al país de destino", country}, {SHIPPED, "En reparto", "Centro de distribución local"},
                {"DELIVERED", "Entregado al destinatario", country},};
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
            log.warn("YunExpress: sin trazabilidad para {} -> {} {}", trackingNumber, response.path("code").asText(""),
                    response.path("msg").asText(""));
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
            steps.add(new TrackingStep(status, description, location(event, countryCode), parseInstant(event)));
        }
        steps.sort(Comparator.comparing(TrackingStep::occurredAt));
        OrderStatus current = steps.isEmpty() ? OrderStatus.FORWARDED : steps.get(steps.size() - 1).status();
        return new TrackingSnapshot(current, steps);
    }

    /** Ubicación legible del evento: la más específica que informe YunExpress. */
    private static String location(JsonNode event, String countryCode) {
        for (String field : new String[]{"process_location", "process_city", "process_province", "process_country"}) {
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
        for (String field : new String[]{"process_utc_time", "process_time"}) {
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

    /** Bultos del pedido con sus partidas arancelarias, a partir del reparto real en bultos. */
    private java.util.List<CustomsDutyLinesService.DutyParcel> dutyParcelsOf(Order order) {
        java.util.List<CustomsDutyLinesService.Line> lines = new java.util.ArrayList<>();
        for (OrderItem item : order.getItems()) {
            ProductEntity product = item.getProductId() != null
                    ? productRepository.findById(item.getProductId()).orElse(null)
                    : null;
            if (product == null) {
                continue;
            }
            ProductVariantEntity variant = variantOf(product, item);
            lines.add(new CustomsDutyLinesService.Line(product.getId(), product.getHsCode(),
                    CustomsDutyLinesService.declaredDescriptionOf(product), product.getCountryOfOrigin(),
                    Math.max(1, item.getQuantity()), item.getUnitPriceCents(),
                    ParcelAggregator.unitWeightGrams(product, variant), dimension(product, variant, Dimension.LENGTH),
                    dimension(product, variant, Dimension.WIDTH), dimension(product, variant, Dimension.HEIGHT),
                    ParcelAggregator.hasBattery(product)));
        }
        // Con el MISMO canal y país que usa el reparto real: si el derecho se contase sobre otro número de
        // bultos, lo cobrado al cliente y lo liquidado en aduana dejarían de coincidir.
        return customsDutyLines.parcelsOf(lines, limitChannelOf(order), order.getShippingCountry());
    }

}
