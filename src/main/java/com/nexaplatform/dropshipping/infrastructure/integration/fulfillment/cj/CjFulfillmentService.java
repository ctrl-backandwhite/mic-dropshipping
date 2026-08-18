package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.cj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.application.service.CustomsValuationService;
import com.nexaplatform.dropshipping.application.service.ParcelAggregator;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.enums.TaxMode;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.domain.model.ShippingOption;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentFailure;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * CJ Dropshipping como transportista de la plataforma: cotiza, despacha y sigue.
 *
 * <p>Aquí no se lee ni se escribe JSON de CJ: eso vive en piezas con sus propias pruebas
 * ({@link CjFreightReader}, {@link CjTrackReader}, {@link CjInventoryLookup}), porque son las que
 * guardan lo que se midió contra la API de verdad. Lo que se decide en esta clase es lo que solo se
 * puede decidir con el pedido delante, y cada decisión tiene un precio si se toma mal:
 *
 * <ul>
 *   <li><b>{@code isSandbox} lo manda el entorno, no la configuración.</b> Un pedido real emitido en
 *       modo prueba <b>no se envía nunca</b> y CJ responde que todo fue bien: no hay error, no hay guía
 *       y nadie se entera hasta que el cliente reclama. Por eso en {@code pre} y {@code pro} va a cero
 *       aunque la propiedad diga lo contrario — el mismo criterio que ya tiene el modo simulado de
 *       YunExpress, y por la misma razón: basta con que alguien copie un {@code .env} de local.</li>
 *   <li><b>Se despacha por la línea que se cobró</b>, no por la más barata de ahora. Entre el cobro y
 *       el despacho la tarifa de CJ cambia; despachar por otra es cobrar un porte y pagar otro.</li>
 *   <li><b>Sin {@code vid} no hay guía.</b> El puente entre nuestro catálogo y el de CJ es el SKU, que
 *       el dueño teclea a mano al depositar la mercancía. Cuando no cuadra, el fallo dice qué SKU se
 *       buscó, que es lo único que delata la errata.</li>
 * </ul>
 *
 * <p><b>Cobertura.</b> CJ envía prácticamente a cualquier país y su API <b>no publica la lista</b>: no
 * hay endpoint de países cubiertos, solo la cotización, que devuelve vacío donde no llega. Así que se
 * acepta cualquier destino con código ISO-2 y es la propia cotización la que decide —cero opciones es
 * «aquí no llego»—. {@link #supportedCountries()} devuelve vacío a propósito: inventar una lista sería
 * prometerle al cliente destinos que nadie ha comprobado, y el banner de cobertura ya se pinta con la
 * tabla de zonas del otro transportista.
 */
@Slf4j
@Service
public class CjFulfillmentService implements FulfillmentProvider {

    /** Cómo se llama este transportista dentro de la plataforma. Es lo que se guarda en el pedido. */
    public static final String NOMBRE = "CJ";

    /**
     * Lo que el cliente ve como transportista. Es <b>el mismo texto que usa YunExpress</b> a propósito:
     * las opciones se le enseñan mezcladas y sin marca (decisión del dueño), y que el nombre cambiara
     * según quién llevara el paquete delataría justo lo que se decidió no enseñar.
     */
    private static final String NOMBRE_PUBLICO = "Standard Shipping";

    /**
     * Portes por peso y medidas. Es {@code freightCalculateTip} y no {@code freightCalculate} porque el
     * segundo exige el {@code vid} del catálogo de CJ, y al cotizar todavía no hay ninguno: nuestros
     * productos vienen de 1688.
     */
    static final String RUTA_PORTES = "/api2.0/v1/logistic/freightCalculateTip";

    /** Alta del pedido en CJ. Es la llamada que emite la guía y la que cobra. */
    static final String RUTA_CREAR_PEDIDO = "/api2.0/v1/shopping/order/createOrderV3";

    /** Seguimiento por número de guía. Admite varios separados por coma; aquí se pregunta de uno en uno. */
    static final String RUTA_SEGUIMIENTO = "/api2.0/v1/logistic/trackInfo";

    /** IOSS de CJ: es él quien liquida el IVA de importación con su propio número fiscal. */
    private static final int IOSS_DE_CJ = 3;

    /** Sin régimen de importación prepagado: el impuesto se liquida en destino. */
    private static final int SIN_IOSS = 1;

    /** Nada que contar: el envío sigue donde estaba. Nunca se inventa un avance. */
    private static final TrackingSnapshot SIN_NOVEDAD =
            new TrackingSnapshot(OrderStatus.FORWARDED, List.of());

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Lo que CJ ofreció para un destino, por si hace falta al despachar (ver
     * {@link #opcionElegidaDe(Order, ParcelSpec)}). La clave lleva el país porque el plazo de una misma
     * línea cambia de un destino a otro; el nombre no, pero el plazo se enseña al cliente.
     */
    private final ConcurrentMap<String, ShippingOption> opcionesConocidas = new ConcurrentHashMap<>();

    private final CjAuthService auth;
    private final CjInventoryLookup inventario;
    /** De dónde sale el modo de despacho fiscal del destino: la tabla {@code country_customs_rule}. */
    private final CustomsValuationService aduana;
    /** El peso del bulto sale del catálogo, igual que en la vista previa del checkout. */
    private final ProductRepository productos;
    /** Para saber si el entorno es real y, por tanto, si el modo prueba está prohibido. */
    private final Environment entorno;
    private final LlamadaACj llamada;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    @Value("${nexadrop.cj.enabled:false}")
    private boolean habilitado;

    /**
     * Modo prueba de CJ. No es otro servidor: es el mismo, con {@code isSandbox=1} al crear el pedido.
     * Solo se obedece fuera de {@code pre} y {@code pro} — ver {@link #pruebasPermitidas()}.
     */
    @Value("${nexadrop.cj.sandbox:false}")
    private boolean modoPruebas;

    /** País desde el que sale la mercancía depositada en CJ. */
    @Value("${nexadrop.cj.from-country:CN}")
    private String paisDeOrigen;

    @Value("${nexadrop.cj.base-url:https://developers.cjdropshipping.com}")
    private String baseUrl;

    @Value("${nexadrop.cj.fulfillment-timeout-seconds:20}")
    private int timeoutSegundos;

    @Autowired
    public CjFulfillmentService(CjAuthService auth, CjInventoryLookup inventario,
            CustomsValuationService aduana, ProductRepository productos, Environment entorno) {
        this.auth = auth;
        this.inventario = inventario;
        this.aduana = aduana;
        this.productos = productos;
        this.entorno = entorno;
        this.llamada = this::llamarACj;
    }

    /** Para las pruebas: sustituye la llamada a CJ sin tocar ninguna de las decisiones de arriba. */
    CjFulfillmentService(CjInventoryLookup inventario, CustomsValuationService aduana,
            ProductRepository productos, Environment entorno, LlamadaACj llamada) {
        this.auth = null;
        this.inventario = inventario;
        this.aduana = aduana;
        this.productos = productos;
        this.entorno = entorno;
        this.llamada = llamada;
    }

    /**
     * Una llamada a CJ, aparte para poder probar el resto sin red.
     *
     * <p>Se sustituye la llamada entera y no solo el transporte porque lo que hay que poder mirar en
     * una prueba es el <b>cuerpo exacto</b> que se manda: los dos fallos que ya costaron tiempo con esta
     * API —el peso en la unidad equivocada y el modo prueba donde no toca— se ven ahí y en ningún otro
     * sitio, porque CJ responde «correcto» en los dos casos.
     */
    @FunctionalInterface
    public interface LlamadaACj {

        /** El cuerpo bruto de lo que conteste CJ; {@code cuerpo} nulo significa GET. */
        String responder(String ruta, String cuerpo);
    }

    @Override
    public String nombre() {
        return NOMBRE;
    }

    // ── Cobertura ────────────────────────────────────────────────────────────────────────────────

    /**
     * ¿Se le puede preguntar por este destino? Ver el criterio de cobertura en el javadoc de la clase:
     * CJ no publica su lista de países, así que quien decide de verdad es la cotización.
     *
     * <p>Con la integración apagada devuelve false, y eso es lo que hace que el enrutador ni siquiera
     * llame: sin esto se gastaría una petición —CJ admite una por segundo— en el camino del checkout.
     */
    @Override
    public boolean isSupported(String countryCode) {
        return habilitado && esCodigoDePais(countryCode);
    }

    /**
     * Vacío a propósito: CJ no publica los países a los que llega y una lista inventada aquí acabaría
     * en el banner de cobertura como una promesa.
     */
    @Override
    public List<SupportedCountry> supportedCountries() {
        return List.of();
    }

    // ── Cotización ───────────────────────────────────────────────────────────────────────────────

    /**
     * Lo que CJ cobra por llevar este bulto a este destino, ya en el formato del checkout.
     *
     * <p><b>No lanza nunca.</b> El enrutador tolera que un transportista se caiga y siga vendiendo con
     * el otro, pero lo que tolera es una respuesta sin opciones, no una excepción: si esta se propagara
     * se perdería también la venta que el otro transportista sí podía hacer.
     */
    @Override
    public ShippingQuote quote(String countryCode, ParcelSpec parcel) {
        if (!isSupported(countryCode)) {
            return ShippingQuote.unsupported(countryCode);
        }
        List<ShippingOption> opciones;
        try {
            opciones = opcionesDe(countryCode, parcel);
        } catch (RuntimeException e) {
            log.warn("CJ no pudo cotizar a {}: {}", countryCode, e.getMessage());
            return ShippingQuote.unsupported(countryCode);
        }
        if (opciones.isEmpty()) {
            return ShippingQuote.unsupported(countryCode);
        }
        ShippingOption barata = opciones.get(0);
        return new ShippingQuote(true, normalizarPais(countryCode), barata.amountUsdCents(),
                NOMBRE_PUBLICO, NOMBRE_PUBLICO, barata.etaMinDays(), barata.etaMaxDays(), null, opciones);
    }

    /**
     * Las líneas que CJ ofrece, de más barata a más cara.
     *
     * <p>El orden importa aunque el enrutador vuelva a ordenar la lista fundida: la cabecera de la
     * cotización es lo que se cobra cuando el cliente no elige, y {@code ShippingOptionResolver} cae a
     * la primera opción. Sin ordenar, «lo más barato» sería lo que CJ pusiera primero.
     *
     * <p>El valor de la mercancía va a cero a propósito: el bulto no lo lleva, CJ solo lo usa para
     * estimar impuestos —que calcula la plataforma, no él— y mandarlo en la cotización y no al
     * despachar haría que las dos consultas devolvieran listas distintas.
     */
    private List<ShippingOption> opcionesDe(String pais, ParcelSpec bulto) {
        String cuerpo = CjFreightReader.cuerpoDeConsulta(normalizarPais(paisDeOrigen),
                normalizarPais(pais), "", "", "", bulto.weightGrams(), aCentimetros(bulto.lengthMm()),
                aCentimetros(bulto.widthMm()), aCentimetros(bulto.heightMm()), 0);
        List<ShippingOption> opciones = new ArrayList<>(
                CjFreightReader.leer(llamada.responder(RUTA_PORTES, cuerpo)));
        opciones.sort(Comparator.comparingInt(ShippingOption::amountUsdCents)
                .thenComparingInt(ShippingOption::etaMaxDays));
        for (ShippingOption opcion : opciones) {
            opcionesConocidas.put(clave(pais, opcion.code()), opcion);
        }
        return opciones;
    }

    // ── Despacho ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Emite la guía en CJ ({@code createOrderV3}).
     *
     * <p>Un solo bulto: CJ tarifica y despacha el pedido entero de una vez y no publica los topes por
     * canal que obligan a repartir con el otro transportista. Si algún día los impone, el reparto se
     * hace aquí y esta lista deja de tener un elemento.
     *
     * <p>Todo lo que puede salir mal se convierte en {@link FulfillmentFailure}: el pedido ya está
     * cobrado, así que lo que toca es dejarlo en la bandeja de incidencias con el motivo escrito, no
     * propagar una excepción cruda que el admin no pueda leer.
     */
    @Override
    public List<FulfillmentResult> createShipments(Order order) {
        if (!habilitado) {
            throw new FulfillmentFailure(FulfillmentFailure.Kind.TRANSIENT,
                    "CJ está desactivado (nexadrop.cj.enabled=false): no se emite una guía inventada.");
        }
        List<Articulo> articulos = articulosDe(order);
        ParcelSpec bulto = bultoDe(order);
        ShippingOption elegida = opcionElegidaDe(order, bulto);
        String respuesta;
        try {
            respuesta = llamada.responder(RUTA_CREAR_PEDIDO, cuerpoDeCreacion(order, elegida, articulos));
        } catch (RuntimeException e) {
            throw FulfillmentFailure.of(e);
        }
        JsonNode datos = datosDeCreacion(respuesta, order);
        String referencia = texto(datos, "orderId");
        return List.of(new FulfillmentResult(NOMBRE_PUBLICO, numeroDeSeguimientoDe(datos), referencia,
                elegida.etaMaxDays(), 1, bulto.weightGrams(), valorDeclaradoCents(order), elegida.code(),
                contenidoDe(order), declaracionDe(order)));
    }

    /**
     * La línea por la que se cobró el envío, con su nombre tal y como lo escribe CJ.
     *
     * <p>{@code createOrderV3} pide {@code logisticName}, un nombre, mientras que el identificador de la
     * opción es un número largo: son dos caras de la misma línea. <b>El nombre se guarda en el pedido al
     * cobrar</b> ({@code shipping_channel_name}, v147), así que lo normal es no preguntarle nada a CJ.
     * No es un ahorro cosmético: el despacho ocurre horas después, CJ limita a una petición por segundo
     * y, si para entonces hubiera dejado de ofrecer esa línea, el pedido se quedaría sin despachar por
     * un dato que ya teníamos.
     *
     * <p>La consulta a CJ queda como respaldo para los pedidos anteriores a esa columna. Se hace con el
     * mismo bulto y el mismo destino que la del checkout —sin código postal ni ciudad, con el mismo
     * peso— para que devuelva la misma lista; si se afinara más aquí, CJ podría ofrecer un juego de
     * líneas distinto y la que se cobró no aparecería. Su fallo <b>no puede despachar por otra línea</b>:
     * se abandona con el motivo escrito.
     */
    private ShippingOption opcionElegidaDe(Order pedido, ParcelSpec bulto) {
        String codigo = pedido.getShippingChannelCode();
        if (codigo == null || codigo.isBlank()) {
            throw new FulfillmentFailure(FulfillmentFailure.Kind.PERMANENT,
                    "El pedido " + pedido.getOrderNumber() + " no dice por qué línea de CJ se cobró el "
                            + "envío: elegir una aquí sería despachar por un porte distinto del cobrado.");
        }
        String pais = pedido.getShippingCountry();
        // Camino normal: el nombre viajó con el pedido desde el cobro. El plazo no se guarda porque solo
        // alimenta el ETA informativo de la guía; el porte ya se cobró y no se recalcula aquí.
        String nombreGuardado = pedido.getShippingChannelName();
        if (nombreGuardado != null && !nombreGuardado.isBlank()) {
            return new ShippingOption(codigo.trim(), nombreGuardado, 0, 0, 0, NOMBRE);
        }
        String buscado = clave(pais, codigo.trim());
        ShippingOption recordada = opcionesConocidas.get(buscado);
        if (recordada != null) {
            return recordada;
        }
        try {
            opcionesDe(pais, bulto);
        } catch (RuntimeException e) {
            throw new FulfillmentFailure(FulfillmentFailure.Kind.TRANSIENT,
                    "CJ no contestó al preguntarle por la línea " + codigo + " del pedido "
                            + pedido.getOrderNumber() + ": " + e.getMessage());
        }
        ShippingOption opcion = opcionesConocidas.get(buscado);
        if (opcion == null) {
            throw new FulfillmentFailure(FulfillmentFailure.Kind.TRANSIENT,
                    "CJ ya no ofrece la línea " + codigo + " para " + pais + " (pedido "
                            + pedido.getOrderNumber() + "): no se despacha por otra distinta de la cobrada.");
        }
        return opcion;
    }

    /**
     * Las líneas del pedido traducidas al identificador de variante que CJ exige.
     *
     * <p>El SKU es todo el puente entre los dos catálogos —el dueño deposita la mercancía en CJ
     * tecleándolo a mano—, así que cuando no cuadra el fallo lleva el SKU escrito: es lo único que
     * permite ver la errata sin entrar en el panel de CJ a buscarla.
     */
    private List<Articulo> articulosDe(Order pedido) {
        List<Articulo> articulos = new ArrayList<>();
        for (OrderItem linea : lineasDe(pedido)) {
            String sku = linea.getSkuSnapshot();
            if (sku == null || sku.isBlank()) {
                throw new FulfillmentFailure(FulfillmentFailure.Kind.PERMANENT,
                        "Una línea del pedido " + pedido.getOrderNumber() + " no tiene SKU, y sin SKU no "
                                + "se puede saber qué mercancía sacar del almacén de CJ.");
            }
            String vid = inventario.variantIdDe(sku).orElseThrow(() -> new FulfillmentFailure(
                    FulfillmentFailure.Kind.PERMANENT, CjInventoryLookup.mensajeDeSkuNoEncontrado(sku)));
            articulos.add(new Articulo(vid, Math.max(1, linea.getQuantity())));
        }
        if (articulos.isEmpty()) {
            throw new FulfillmentFailure(FulfillmentFailure.Kind.PERMANENT,
                    "El pedido " + pedido.getOrderNumber() + " no tiene líneas que despachar.");
        }
        return articulos;
    }

    /**
     * El cuerpo de {@code createOrderV3}.
     *
     * <p>Se construye a mano, como el de la cotización, para que <b>lo que se manda esté a la vista</b>:
     * el valor de {@code isSandbox} es la diferencia entre un pedido que sale y uno que no sale nunca, y
     * en un objeto con anotaciones ese número queda a tres ficheros de distancia de la regla que lo fija.
     */
    private String cuerpoDeCreacion(Order pedido, ShippingOption elegida, List<Articulo> articulos) {
        return """
                {"orderNumber":"%s","shippingCountryCode":"%s","shippingCountry":"%s",\
                "shippingProvince":"%s","shippingCity":"%s","shippingAddress":"%s",\
                "shippingCustomerName":"%s","shippingPhone":"%s","shippingZip":"%s",\
                "logisticName":"%s","fromCountryCode":"%s","iossType":%d,"isSandbox":%d,\
                "products":[%s]}\
                """.formatted(
                escapar(pedido.getOrderNumber()),
                normalizarPais(pedido.getShippingCountry()),
                escapar(nombreDelPais(pedido.getShippingCountry())),
                escapar(pedido.getShippingState()),
                escapar(pedido.getShippingCity()),
                escapar(direccionDe(pedido)),
                escapar(pedido.getShippingFullName()),
                escapar(pedido.getShippingPhone()),
                escapar(pedido.getShippingPostalCode()),
                escapar(elegida.name()),
                normalizarPais(paisDeOrigen),
                iossTypeDe(pedido.getShippingCountry()),
                isSandbox(),
                articulosJson(articulos));
    }

    /**
     * {@code isSandbox}: 1 solo donde está permitido probar.
     *
     * <p><b>Esta es la línea que no se puede tocar.</b> Un pedido emitido con 1 en producción se queda
     * en el sandbox de CJ: no se envía, no se cobra, no da error y no aparece en ningún panel de
     * incidencias. Se descubre cuando el cliente pregunta dónde está su paquete.
     */
    private int isSandbox() {
        return modoPruebas && pruebasPermitidas() ? 1 : 0;
    }

    /**
     * ¿Está permitido el modo prueba en este entorno? Solo fuera de {@code pre} y {@code pro}.
     *
     * <p>Mismo criterio que el modo simulado de YunExpress ({@code mockAllowed}) y por la misma razón:
     * la propiedad de configuración viaja en los {@code .env}, y basta con copiar uno de local para
     * dejar una tienda entera emitiendo pedidos de mentira. El perfil, en cambio, lo fija el despliegue.
     */
    private boolean pruebasPermitidas() {
        return !entorno.acceptsProfiles(Profiles.of("pro", "pre"));
    }

    /**
     * {@code iossType}: 3 (el IOSS de CJ) donde el transportista liquida el IVA de importación con su
     * propio número fiscal, 1 donde no hay régimen prepagado que declarar.
     *
     * <p>Se pregunta a la tabla de países y no a una lista de la UE escrita aquí: el régimen prepagable
     * de hoy es el IOSS de la UE, pero es la misma marca que ya decide si se le pide el prepago a
     * YunExpress, y dos listas distintas del mismo hecho acaban divergiendo.
     */
    private int iossTypeDe(String pais) {
        return aduana.carrierPrepaysVatFor(pais) ? IOSS_DE_CJ : SIN_IOSS;
    }

    private static String articulosJson(List<Articulo> articulos) {
        List<String> partes = new ArrayList<>();
        for (Articulo articulo : articulos) {
            partes.add("{\"vid\":\"%s\",\"quantity\":%d}".formatted(escapar(articulo.vid()),
                    articulo.cantidad()));
        }
        return String.join(",", partes);
    }

    /** Lo que CJ devolvió al crear el pedido, o el fallo ya clasificado si lo rechazó. */
    private JsonNode datosDeCreacion(String cuerpo, Order pedido) {
        JsonNode raiz;
        try {
            raiz = JSON.readTree(cuerpo == null ? "" : cuerpo);
        } catch (IOException e) {
            throw new FulfillmentFailure(FulfillmentFailure.Kind.TRANSIENT,
                    "CJ devolvió una respuesta ilegible al crear el pedido " + pedido.getOrderNumber());
        }
        // CJ contesta 200 de HTTP también cuando rechaza: sin mirar `code` y `result` se daría por
        // despachado un pedido sin guía, y el cliente recibiría el correo de envío de un paquete que no
        // existe.
        if (raiz.path("code").asInt() != 200 || !raiz.path("result").asBoolean()) {
            throw FulfillmentFailure.from("CJ rechazó el pedido " + pedido.getOrderNumber() + " (código "
                    + raiz.path("code").asInt() + "): " + raiz.path("message").asText("sin mensaje"));
        }
        JsonNode datos = raiz.path("data");
        if (datos.isMissingNode() || datos.isNull()) {
            throw new FulfillmentFailure(FulfillmentFailure.Kind.TRANSIENT,
                    "CJ dijo que aceptó el pedido " + pedido.getOrderNumber() + " pero no devolvió datos.");
        }
        return datos;
    }

    /**
     * Con qué número se sigue el envío.
     *
     * <p>{@code createOrderV3} <b>no devuelve la guía</b>: la asigna después y la comunica por el aviso
     * de logística. Hasta entonces se guarda el identificador del envío en CJ, que es con el que se
     * puede preguntar por él y con el que el aviso se cruza con el pedido. Dejarlo vacío sería peor:
     * el despacho volvería a intentarse y se emitiría una segunda guía del mismo pedido.
     */
    private static String numeroDeSeguimientoDe(JsonNode datos) {
        for (String campo : List.of("trackNumber", "shipmentOrderId", "orderId")) {
            String valor = texto(datos, campo);
            if (!valor.isBlank()) {
                return valor;
            }
        }
        return "";
    }

    // ── Seguimiento ──────────────────────────────────────────────────────────────────────────────

    /**
     * Dónde está el envío según CJ.
     *
     * <p>{@code trackInfo} devuelve el estado <b>actual</b>, no el histórico, así que sale un paso por
     * sondeo. Los repetidos los quita {@code FulfillmentService.collectNewSteps}, que deduplica por
     * estado y descripción — de ahí que la descripción venga del enum y en español, y no del texto en
     * inglés de CJ, que cambia de redacción y crearía un hito «nuevo» en cada sondeo.
     */
    @Override
    public TrackingSnapshot track(String trackingNumber, Instant forwardedAt, String countryCode) {
        if (!habilitado || trackingNumber == null || trackingNumber.isBlank()) {
            return SIN_NOVEDAD;
        }
        List<CjTrackReader.Seguimiento> seguimientos;
        try {
            seguimientos = CjTrackReader.leer(llamada.responder(RUTA_SEGUIMIENTO + "?trackNumber="
                    + URLEncoder.encode(trackingNumber.trim(), StandardCharsets.UTF_8), null));
        } catch (RuntimeException e) {
            // Perder un sondeo se arregla en el siguiente; romperlo deja sin avanzar también a los
            // pedidos del otro transportista, porque el planificador es el mismo.
            log.warn("CJ no devolvió seguimiento de {}: {}", trackingNumber, e.getMessage());
            return SIN_NOVEDAD;
        }
        Optional<CjTrackReader.Seguimiento> suyo = seguimientos.stream()
                .filter(s -> trackingNumber.trim().equals(s.numeroSeguimiento())).findFirst();
        if (suyo.isEmpty()) {
            return SIN_NOVEDAD;
        }
        CjTrackStatus estado = suyo.get().estado();
        List<TrackingStep> pasos = new ArrayList<>();
        String descripcion = estado.descripcion();
        if (descripcion != null) {
            // Sin texto no se escribe el hito: una incidencia no tiene traducción y reutilizar la de
            // «en tránsito» le diría al cliente que su paquete avanza justo cuando CJ avisa de que no.
            pasos.add(new TrackingStep(estado.estadoPedido(), descripcion, null, suyo.get().momento()));
        }
        return new TrackingSnapshot(estado.estadoPedido(), pasos);
    }

    // ── Impuestos ────────────────────────────────────────────────────────────────────────────────

    /**
     * DDP o DDU según el destino, leído de {@code country_customs_rule} — la misma fuente que usa
     * YunExpress. Que dos transportistas despacharan el mismo país en regímenes distintos significaría
     * cobrarle el impuesto al cliente unas veces sí y otras no, según quién llevara el paquete.
     */
    @Override
    public TaxMode taxModeFor(String countryCode) {
        return aduana.taxModeFor(countryCode);
    }

    // ── El bulto y lo que se archiva ─────────────────────────────────────────────────────────────

    /**
     * El bulto del pedido, armado con el <b>mismo agregador</b> que la vista previa del checkout: si
     * aquí se pesara de otra forma, se cotizaría con un peso y se despacharía con otro.
     */
    private ParcelSpec bultoDe(Order pedido) {
        ParcelAggregator agregador = new ParcelAggregator();
        for (OrderItem linea : lineasDe(pedido)) {
            ProductEntity producto = linea.getProductId() == null ? null
                    : productos.findById(linea.getProductId()).orElse(null);
            if (producto == null) {
                agregador.addUnknown(linea.getQuantity());
            } else {
                agregador.add(producto, varianteDe(producto, linea), linea.getQuantity());
            }
        }
        return agregador.build();
    }

    private static ProductVariantEntity varianteDe(ProductEntity producto, OrderItem linea) {
        if (linea.getVariantId() == null || producto.getVariants() == null) {
            return null;
        }
        return producto.getVariants().stream()
                .filter(variante -> linea.getVariantId().equals(variante.getId()))
                .findFirst().orElse(null);
    }

    /** Qué líneas del pedido —y cuántas unidades— van en el bulto: aquí, todas. */
    private static List<ParcelContent> contenidoDe(Order pedido) {
        List<OrderItem> lineas = lineasDe(pedido);
        List<ParcelContent> contenido = new ArrayList<>();
        for (int i = 0; i < lineas.size(); i++) {
            contenido.add(new ParcelContent(i, Math.max(1, lineas.get(i).getQuantity())));
        }
        return contenido;
    }

    /**
     * Copia de lo que se le transmitió a CJ.
     *
     * <p>Va sin líneas de declaración a propósito, y no por ahorrar: a CJ <b>no se le declara la
     * mercancía</b> —despacha desde su propio catálogo, con el {@code vid}—, así que inventar aquí unas
     * partidas arancelarias sería archivar como transmitido algo que nunca salió de esta máquina. El
     * destinatario sí se transmite, y sí se archiva: la dirección del pedido puede corregirse después y
     * entonces ya no diría a dónde se mandó el paquete.
     */
    private static ShipmentDeclaration declaracionDe(Order pedido) {
        String nombre = pedido.getShippingFullName() == null ? "" : pedido.getShippingFullName().trim();
        int corte = nombre.indexOf(' ');
        String nombrePila = corte > 0 ? nombre.substring(0, corte) : nombre;
        String apellidos = corte > 0 ? nombre.substring(corte + 1).trim() : "";
        List<String> lineasDireccion = new ArrayList<>();
        anadirSiHay(lineasDireccion, pedido.getShippingLine1());
        anadirSiHay(lineasDireccion, pedido.getShippingLine2());
        return new ShipmentDeclaration(new DeclaredReceiver(nombrePila, apellidos,
                normalizarPais(pedido.getShippingCountry()), pedido.getShippingState(),
                pedido.getShippingCity(), lineasDireccion, pedido.getShippingPostalCode(),
                pedido.getShippingPhone(), pedido.getShippingEmail()), List.of());
    }

    /** Valor de la mercancía del pedido en céntimos, que es lo que viajó dentro del bulto. */
    private static int valorDeclaradoCents(Order pedido) {
        int total = 0;
        for (OrderItem linea : lineasDe(pedido)) {
            total += linea.getLineTotalCents() > 0
                    ? linea.getLineTotalCents()
                    : linea.getUnitPriceCents() * Math.max(1, linea.getQuantity());
        }
        return total;
    }

    // ── Menudencias ──────────────────────────────────────────────────────────────────────────────

    /** Una línea de {@code products[]}: la variante de CJ y cuántas unidades salen de su almacén. */
    private record Articulo(String vid, int cantidad) {
    }

    private static List<OrderItem> lineasDe(Order pedido) {
        return pedido.getItems() == null ? List.of() : pedido.getItems();
    }

    private static void anadirSiHay(List<String> destino, String valor) {
        if (valor != null && !valor.isBlank()) {
            destino.add(valor.trim());
        }
    }

    /** La dirección en una sola línea, que es como la pide CJ. */
    private static String direccionDe(Order pedido) {
        List<String> lineas = new ArrayList<>();
        anadirSiHay(lineas, pedido.getShippingLine1());
        anadirSiHay(lineas, pedido.getShippingLine2());
        return String.join(", ", lineas);
    }

    /**
     * El nombre del país en inglés, que CJ pide además del código.
     *
     * <p>Sale del JDK y no de una tabla nuestra: el dato que manda es {@code shippingCountryCode} —es el
     * que CJ usa para enrutar— y mantener aquí una lista de doscientos nombres solo para acompañarlo
     * sería otra cosa que se queda desactualizada sin que nadie lo note.
     */
    private static String nombreDelPais(String codigo) {
        if (!esCodigoDePais(codigo)) {
            return "";
        }
        return Locale.of("", normalizarPais(codigo)).getDisplayCountry(Locale.ENGLISH);
    }

    private static boolean esCodigoDePais(String codigo) {
        if (codigo == null) {
            return false;
        }
        String limpio = codigo.trim();
        return limpio.length() == 2 && limpio.chars().allMatch(Character::isLetter);
    }

    private static String normalizarPais(String codigo) {
        return codigo == null ? "" : codigo.trim().toUpperCase(Locale.ROOT);
    }

    /** Clave de la opción recordada: la misma línea tiene otro plazo según a dónde vaya. */
    private static String clave(String pais, String codigoDeOpcion) {
        return normalizarPais(pais) + "|" + codigoDeOpcion;
    }

    /** De milímetros a centímetros enteros, que es la unidad de las medidas en CJ. */
    private static int aCentimetros(int milimetros) {
        return milimetros <= 0 ? 0 : (int) Math.round(milimetros / 10.0);
    }

    private static String texto(JsonNode datos, String campo) {
        JsonNode valor = datos.path(campo);
        // asText() sobre un número devuelve sus cifras: los identificadores de CJ tienen diecinueve y
        // pueden llegar sin comillas, como ya pasó con el openId de la autenticación.
        return valor.isMissingNode() || valor.isNull() ? "" : valor.asText("");
    }

    /**
     * Deja un texto listo para meterlo entre comillas en el JSON.
     *
     * <p>La dirección la escribe el cliente: una comilla o una barra invertida en el nombre de la calle
     * rompería el cuerpo entero y el pedido se quedaría sin despachar por un apóstrofo.
     */
    private static String escapar(String valor) {
        if (valor == null) {
            return "";
        }
        StringBuilder salida = new StringBuilder(valor.length());
        for (char caracter : valor.toCharArray()) {
            switch (caracter) {
                case '"' -> salida.append("\\\"");
                case '\\' -> salida.append("\\\\");
                case '\n', '\r', '\t' -> salida.append(' ');
                default -> {
                    if (caracter >= ' ') {
                        salida.append(caracter);
                    }
                }
            }
        }
        return salida.toString().trim();
    }

    /** La llamada de verdad a CJ, firmada con el token vigente. */
    private String llamarACj(String ruta, String cuerpo) {
        HttpRequest.Builder constructor = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + ruta))
                .timeout(Duration.ofSeconds(timeoutSegundos))
                .header("Content-Type", "application/json")
                .header("CJ-Access-Token", auth.tokenVigente());
        HttpRequest peticion = (cuerpo == null
                ? constructor.GET()
                : constructor.POST(HttpRequest.BodyPublishers.ofString(cuerpo, StandardCharsets.UTF_8)))
                .build();
        try {
            HttpResponse<String> respuesta = http.send(peticion, HttpResponse.BodyHandlers.ofString());
            return respuesta.body();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo contactar con CJ (" + ruta + ").", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Llamada a CJ interrumpida (" + ruta + ").", e);
        }
    }
}
