package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.cj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Traduce un SKU nuestro al identificador de variante de CJ ({@code vid}) que exige {@code createOrderV3}.
 *
 * <p><b>Por qué hace falta.</b> Nuestro catálogo viene de 1688 y no sabe nada del catálogo de CJ, pero
 * CJ no emite una guía sin el {@code vid} de la variante que sale del almacén. El puente lo pone la
 * operativa: la mercancía se compra al proveedor cuando entra el pedido y se manda al almacén de CJ, que
 * la registra <b>con el mismo SKU</b> que ya tiene la variante aquí. Por eso basta con preguntarle a CJ
 * por ese SKU.
 *
 * <p><b>Y por qué se pregunta en el momento de despachar.</b> Porque hasta que CJ no ha recibido el lote,
 * su API no lo devuelve: no hay forma de saberlo antes. El dueño mira en el panel de CJ si ya está
 * recibido y libera el pedido; esta consulta es la que lo confirma contra la API antes de emitir la guía,
 * y la que destapa el caso en que el SKU registrado no coincide con el nuestro.
 *
 * <p><b>Por qué se cachea.</b> El {@code vid} de un SKU no cambia, y CJ limita a <b>una petición por
 * segundo</b>. Un despacho de seis líneas resolviendo cada línea contra la red tardaría seis segundos
 * en el mejor caso y chocaría con el límite en cuanto haya dos pedidos a la vez.
 *
 * <p><b>Y por qué los ausentes también, pero solo un rato.</b> Al principio no se guardaban: la causa
 * habitual de una ausencia es una errata al depositar el lote, y al corregirla en el panel de CJ el
 * siguiente intento tenía que verlo sin esperar. El problema apareció al empezar a preguntar por esto en
 * cada despacho: el planificador reintenta <b>cada minuto</b> los pedidos que esperan mercancía, y como la
 * mercancía tarda días en llegar, eso son días preguntando una vez por minuto y por SKU contra un límite
 * de una petición por segundo —el mismo que usa el checkout para cotizar, que sí está delante del
 * cliente—. Así que ahora se recuerda que no estaba durante {@value #MINUTOS_DE_MEMORIA} minutos: la
 * corrección de una errata sigue viéndose sola, con ese retraso como máximo, y las preguntas bajan a una
 * de cada cinco pasadas.
 *
 * <p><b>Lo que se asume de la API, y por qué está aislado.</b> El endpoint es
 * {@code POST /api2.0/v1/product/stock/privateInventory/querySkuDetailPage}, que según la documentación
 * acepta filtrar por {@code sku} y devuelve, entre otros, {@code sku} y {@code variantId}. <b>No está
 * contrastado contra la API real</b>: al 18-ago-2026 el inventario privado de la cuenta está vacío, así
 * que no hay respuesta que capturar. De los seis endpoints de consulta es el único que cruza las dos
 * cosas que hacen falta —buscar por SKU y devolver el {@code variantId}—: {@code querySpuPage} y
 * {@code querySkuFlowByCondition} no traen el {@code variantId}, {@code querySkuDetailListBySku} tampoco
 * (devuelve lotes y cantidades) y {@code querySkuListByProductId} exige el {@code productId} de CJ, que
 * es justo el dato que no tenemos.
 *
 * <p>Como el contraste está pendiente, el parseo ({@link #leerVariantId}) es estático, no toca la red y
 * admite varias formas de lo mismo: la lista puede venir en {@code data.content}, {@code data.list},
 * {@code data.records}, {@code data.rows} o ser el propio {@code data}, y el identificador puede llamarse
 * {@code variantId} —como dice el inventario— o {@code vid} —como lo pide la creación del pedido—.
 * Ajustarlo cuando haya una respuesta real es tocar dos constantes, no reescribir la clase.
 */
@Slf4j
@Component
public class CjInventoryLookup {

    /** Ruta de consulta del inventario privado. Es de solo lectura: CJ no publica endpoint de salida. */
    private static final String RUTA = "/api2.0/v1/product/stock/privateInventory/querySkuDetailPage";

    /** Nombres bajo los que puede venir la lista de filas. Ver el javadoc de la clase. */
    private static final List<String> NOMBRES_DE_LA_LISTA = List.of("content", "list", "records", "rows");

    /** Nombres bajo los que puede venir el identificador de variante. */
    private static final List<String> NOMBRES_DEL_VARIANT_ID = List.of("variantId", "vid");

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Un SKU puede tener varias filas —una por almacén y por lote—, todas con el mismo {@code variantId}.
     * Cincuenta cabe de sobra y evita paginar por algo que se resuelve en la primera página.
     */
    private static final int FILAS_POR_PAGINA = 50;

    /** Minutos que se recuerda que un SKU todavía no estaba depositado. Ver el javadoc de la clase. */
    public static final int MINUTOS_DE_MEMORIA = 5;

    private static final Duration MEMORIA_DE_LOS_AUSENTES = Duration.ofMinutes(MINUTOS_DE_MEMORIA);

    private final ConcurrentMap<String, String> cache = new ConcurrentHashMap<>();

    /** Cuándo se preguntó por última vez por un SKU que no estaba. */
    private final ConcurrentMap<String, Instant> ausentes = new ConcurrentHashMap<>();

    /** Inyectable para poder comprobar la caducidad sin esperar de verdad. */
    private Clock reloj = Clock.systemUTC();

    private final ConsultaDeInventario consulta;
    private final CjAuthService auth;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    @Value("${nexadrop.cj.base-url:https://developers.cjdropshipping.com}")
    private String baseUrl;

    @Value("${nexadrop.cj.inventory-timeout-seconds:20}")
    private int timeoutSegundos;

    @Autowired
    public CjInventoryLookup(CjAuthService auth) {
        this.auth = auth;
        this.consulta = this::preguntarACj;
    }

    /** Para las pruebas: sustituye la llamada de red sin tocar ni la caché ni el parseo. */
    CjInventoryLookup(ConsultaDeInventario consulta) {
        this(consulta, Clock.systemUTC());
    }

    /** Para las pruebas que necesitan mover el reloj sin esperar de verdad. */
    CjInventoryLookup(ConsultaDeInventario consulta, Clock reloj) {
        this.auth = null;
        this.consulta = consulta;
        this.reloj = reloj;
    }

    /** La consulta al inventario de CJ, aparte para poder probar el resto sin red. */
    @FunctionalInterface
    public interface ConsultaDeInventario {

        /** Devuelve el cuerpo bruto de lo que conteste CJ. */
        String respuestaParaSku(String sku);
    }

    /**
     * El {@code vid} con el que CJ despacha ese SKU, si está depositado en su almacén.
     *
     * <p>Devuelve vacío ante cualquier problema —SKU no depositado, CJ caído, respuesta ilegible— en vez
     * de lanzar: quien llama es el despacho, y un pedido ya cobrado no puede reventar por esto. Cae en la
     * bandeja de incidencias de envío con el SKU escrito, que es lo que permite ver la errata.
     */
    public Optional<String> variantIdDe(String sku) {
        if (sku == null || sku.isBlank()) {
            return Optional.empty();
        }
        String buscado = sku.trim();
        String cacheado = cache.get(buscado);
        if (cacheado != null) {
            return Optional.of(cacheado);
        }
        if (seguiaSinEstarHaceNada(buscado)) {
            return Optional.empty();
        }
        Optional<String> encontrado = preguntar(buscado);
        encontrado.ifPresent(variantId -> {
            cache.put(buscado, variantId);
            ausentes.remove(buscado);
        });
        if (encontrado.isEmpty()) {
            ausentes.put(buscado, reloj.instant());
            log.warn("{}", mensajeDeSkuNoEncontrado(buscado));
        }
        return encontrado;
    }

    /** ¿Se preguntó ya por este SKU hace poco y CJ dijo que no lo tenía? */
    private boolean seguiaSinEstarHaceNada(String sku) {
        Instant ultimaVez = ausentes.get(sku);
        return ultimaVez != null && reloj.instant().isBefore(ultimaVez.plus(MEMORIA_DE_LOS_AUSENTES));
    }

    /**
     * El aviso que se deja cuando un SKU no se puede resolver.
     *
     * <p><b>Lleva el SKU buscado a propósito.</b> La causa más probable no es un fallo del código sino
     * una errata al teclear el código depositando el lote en el panel de CJ, y eso no se ve hasta que un
     * pedido no se puede despachar. Con el código delante se comprueba de un vistazo.
     */
    public static String mensajeDeSkuNoEncontrado(String sku) {
        return "El SKU %s no está en el inventario privado de CJ: no se puede emitir la guía. "
                .formatted(sku)
                + "Comprueba que el lote se dio de alta en CJ con ese código exacto.";
    }

    /** El cuerpo de la consulta, filtrando por SKU. */
    static String cuerpoDeConsulta(String sku) {
        return "{\"pageNum\":1,\"pageSize\":%d,\"sku\":\"%s\"}".formatted(FILAS_POR_PAGINA, texto(sku));
    }

    /**
     * Saca de la respuesta el identificador de la variante <b>cuyo SKU coincide exactamente</b>.
     *
     * <p>La coincidencia exacta no es un detalle: CJ acepta el {@code sku} como filtro de búsqueda, así
     * que una consulta de {@code NX-CAM-AZ-M} puede traer también {@code NX-CAM-AZ-M2} y las tallas
     * hermanas. Quedarse con la primera fila despacharía otra talla, y eso no se descubre hasta que el
     * cliente abre el paquete. Solo se ignoran los espacios de alrededor, que no forman parte del código.
     */
    static Optional<String> leerVariantId(String cuerpo, String sku) {
        JsonNode raiz;
        try {
            raiz = JSON.readTree(cuerpo);
        } catch (IOException e) {
            log.warn("El inventario de CJ devolvió una respuesta ilegible: {}", e.getMessage());
            return Optional.empty();
        }
        if (raiz.path("code").asInt() != 200 || !raiz.path("result").asBoolean()) {
            log.warn("CJ no pudo consultar el inventario ({}): {}", raiz.path("code").asInt(),
                    raiz.path("message").asText("sin mensaje"));
            return Optional.empty();
        }
        String buscado = sku == null ? "" : sku.trim();
        for (JsonNode fila : filas(raiz.path("data"))) {
            if (buscado.equals(fila.path("sku").asText("").trim())) {
                return identificadorDe(fila);
            }
        }
        return Optional.empty();
    }

    /** La lista de filas, venga como venga el envoltorio de paginación. */
    private static Iterable<JsonNode> filas(JsonNode datos) {
        if (datos.isArray()) {
            return datos;
        }
        for (String nombre : NOMBRES_DE_LA_LISTA) {
            JsonNode lista = datos.path(nombre);
            if (lista.isArray()) {
                return lista;
            }
        }
        return List.of();
    }

    private static Optional<String> identificadorDe(JsonNode fila) {
        for (String nombre : NOMBRES_DEL_VARIANT_ID) {
            JsonNode valor = fila.path(nombre);
            // asText() sobre un número devuelve sus cifras: el vid tiene diecinueve y cabe en un long,
            // así que puede llegar sin comillas, como ya pasó con el openId de la autenticación.
            if (!valor.isMissingNode() && !valor.isNull() && !valor.asText("").isBlank()) {
                return Optional.of(valor.asText());
            }
        }
        return Optional.empty();
    }

    /**
     * Una consulta a CJ, con su fallo ya domado.
     *
     * <p>Un transportista caído no puede propagar una excepción hasta el despacho: el pedido está cobrado
     * y lo que toca es dejarlo en la bandeja de incidencias, no perder la traza en un error genérico.
     */
    private Optional<String> preguntar(String sku) {
        try {
            return leerVariantId(consulta.respuestaParaSku(sku), sku);
        } catch (RuntimeException e) {
            log.warn("No se pudo consultar el inventario de CJ para el SKU {}: {}", sku, e.getMessage());
            return Optional.empty();
        }
    }

    private String preguntarACj(String sku) {
        HttpRequest peticion = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + RUTA))
                .timeout(Duration.ofSeconds(timeoutSegundos))
                .header("Content-Type", "application/json")
                .header("CJ-Access-Token", auth.tokenVigente())
                .POST(HttpRequest.BodyPublishers.ofString(cuerpoDeConsulta(sku)))
                .build();
        try {
            HttpResponse<String> respuesta = http.send(peticion, HttpResponse.BodyHandlers.ofString());
            return respuesta.body();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo contactar con el inventario de CJ.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Consulta al inventario de CJ interrumpida.", e);
        }
    }

    /** Evita romper el JSON si un SKU trae comillas; un código así ya sería un error de alta. */
    private static String texto(String valor) {
        return valor == null ? "" : valor.replace("\"", "");
    }
}
