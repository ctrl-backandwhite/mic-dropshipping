package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.application.service.ProductViewHistoryService;
import com.nexaplatform.dropshipping.infrastructure.campaign.ViewedProductsDigestService;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Certificación del HISTORIAL DE PRODUCTOS VISITADOS contra un Postgres real y con el correo capturado tal
 * y como saldría por el cable.
 *
 * <p>Se comprueban las dos mitades de la funcionalidad. La de la pantalla: que abrir una ficha queda
 * anotado, que volver a abrirla NO duplica la entrada —la consolidación es lo único que impide que el
 * historial acabe siendo el mismo producto treinta veces— y que nadie ve el historial de otro. Y la del
 * correo de cada tres días: que a quien no ha mirado nada no se le escribe, que lo de hace más de tres días
 * no entra, que un producto retirado entre medias no rompe el envío, y que las fotos de la cuadrícula
 * viajan DENTRO del mensaje y no como enlaces que el cliente de correo va a bloquear.
 */
@DisplayName("Historial de visitas · lo que has visto, y el recordatorio de cada tres días")
class ProductHistoryFlowIT extends EmailITSupport {

    private static final String VIEWS = "/api/me/product-views";
    private static final String CORREO = "yo@example.com";

    private static final ParameterizedTypeReference<Map<String, Object>> PAGINA =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private ViewedProductsDigestService digestService;

    @Autowired
    private ProductViewHistoryService historyService;

    private UUID userId;
    private String token;
    private String tokenIntruso;
    private UUID camiseta;
    private UUID pantalon;

    @BeforeEach
    void prepararEscenario() {
        seedDivisas();
        userId = crearUsuario(CORREO);
        token = jwt.userToken(userId, CORREO, "USER");
        tokenIntruso = jwt.userToken(crearUsuario("intruso-" + UUID.randomUUID() + "@example.com"),
                "intruso@example.com", "USER");
        camiseta = crearProducto("camiseta", "Camiseta");
        pantalon = crearProducto("pantalon", "Pantalón");
        // Las fotos del correo se leen del bucket por el cliente S3; aquí no hay MinIO, así que se dobla.
        when(objectStorage.bytesFromPublicUrl(anyString())).thenReturn(pngDePrueba(400));
    }

    /* ============================== La pantalla del usuario ============================== */

    @Test
    @DisplayName("abrir una ficha la deja anotada en el historial")
    void abrirUnaFichaLaDejaEnElHistorial() {
        visitar(token, camiseta);

        assertThat(titulosDelHistorial(token)).containsExactly("Camiseta");
    }

    /**
     * El motivo de que la identidad de la fila sea (usuario, producto) y no (usuario, producto, momento).
     * Quien deja la ficha abierta y recarga treinta veces metería treinta filas idénticas: el historial
     * pasaría a ser el mismo producto repetido en pantalla y el correo, una cuadrícula de duplicados.
     */
    @Test
    @DisplayName("visitar la misma ficha varias veces deja UNA sola entrada")
    void visitarLaMismaFichaVariasVecesDejaUnaEntrada() {
        visitar(token, camiseta);
        visitar(token, camiseta);
        visitar(token, camiseta);

        assertThat(titulosDelHistorial(token)).containsExactly("Camiseta");
        assertThat(filasEnBd(userId)).isEqualTo(1);
        // Lo único que se perdería al consolidar —cuántas veces ha vuelto— se conserva en el contador.
        assertThat(visitasContadas(userId, camiseta)).isEqualTo(3);
    }

    @Test
    @DisplayName("el historial se ordena por la visita más reciente, no por la primera")
    void elHistorialSeOrdenaPorLaVisitaMasReciente() {
        visitar(token, camiseta);
        visitar(token, pantalon);
        // Volver sobre la camiseta la devuelve a lo alto de la lista.
        visitar(token, camiseta);

        assertThat(titulosDelHistorial(token)).containsExactly("Camiseta", "Pantalón");
    }

    /**
     * El historial es dato personal: el identificador de usuario sale SIEMPRE de la autenticación. En este
     * proyecto ya hubo IDOR reales (pago, Academy, partner), así que el aislamiento queda fijado aquí.
     */
    @Test
    @DisplayName("un usuario no puede ver ni ensuciar el historial de otro")
    void nadieVeElHistorialDeOtro() {
        visitar(token, camiseta);

        assertThat(titulosDelHistorial(tokenIntruso)).isEmpty();

        // Y si el intruso visita la misma ficha, se la anota en SU historial sin tocar el del dueño.
        visitar(tokenIntruso, camiseta);
        assertThat(filasEnBd(userId)).isEqualTo(1);
        assertThat(visitasContadas(userId, camiseta)).isEqualTo(1);
    }

    @Test
    @DisplayName("sin sesión iniciada no se registra ni se consulta nada")
    void sinSesionNoHayHistorial() {
        // La decisión de producto: no se rastrea a quien no ha iniciado sesión.
        client.post().uri(VIEWS + "/" + camiseta).exchange().expectStatus().isUnauthorized();
        client.get().uri(VIEWS).exchange().expectStatus().isUnauthorized();

        assertThat(filasEnBd(userId)).isZero();
    }

    @Test
    @DisplayName("un producto que no existe se rechaza (404) y no ensucia el historial")
    void unProductoInexistenteSeRechaza() {
        client.post().uri(VIEWS + "/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, bearer(token))
                .exchange().expectStatus().isNotFound();

        assertThat(filasEnBd(userId)).isZero();
    }

    /** Retirar un producto no puede dejar el historial apuntando a una ficha que ya no se sabe pintar. */
    @Test
    @DisplayName("si el producto se retira del catálogo, desaparece del historial sin romper la página")
    void unProductoRetiradoDesapareceDelHistorial() {
        visitar(token, camiseta);
        visitar(token, pantalon);

        borrarProducto(camiseta);

        assertThat(titulosDelHistorial(token)).containsExactly("Pantalón");
    }

    /* ============================== El correo de cada tres días ============================== */

    /** El requisito explícito: si no ha visitado nada en la ventana, NO se le escribe. */
    @Test
    @DisplayName("sin visitas en la ventana no se envía ningún correo")
    void sinVisitasNoSeEnviaCorreo() {
        assertThat(digestService.sendDigests()).isZero();

        despacharCola();
        assertThat(correosPara(CORREO)).isEmpty();
    }

    @Test
    @DisplayName("las visitas de hace más de tres días no entran, y solas no generan correo")
    void lasVisitasFueraDeLaVentanaNoGeneranCorreo() {
        visitar(token, camiseta);
        envejecerVisitas(userId, 5);

        assertThat(digestService.sendDigests()).isZero();

        despacharCola();
        assertThat(correosPara(CORREO)).isEmpty();
    }

    /**
     * La cuadrícula con las fotos DENTRO del mensaje. Con la URL del storage no se verían: en local apunta
     * a localhost —inalcanzable para el proxy de Gmail— y Outlook y Apple Mail bloquean las imágenes
     * remotas de serie. Un correo cuya gracia es la cuadrícula no puede depender de eso.
     */
    @Test
    @DisplayName("el correo lleva la cuadrícula y sus fotos incrustadas en el propio mensaje")
    void elCorreoLlevaLasFotosDentro() {
        visitar(token, camiseta);
        visitar(token, pantalon);

        assertThat(digestService.sendDigests()).isEqualTo(1);
        despacharCola();

        MimeMessage correo = unicoCorreoPara(CORREO);
        assertThat(asuntoDe(correo)).isEqualTo("Lo que has estado mirando en NX036");
        String html = cuerpoHtml(correo);
        assertThat(html).contains("Camiseta").contains("Pantalón")
                .contains("src=\"cid:vp0\"").contains("src=\"cid:vp1\"")
                // Enlace de baja: es comunicación comercial y tiene que poder cortarse de un clic.
                .contains("/api/campaigns/unsubscribe");
        // Y las dos referencias tienen su parte incrustada de verdad, no un hueco.
        for (String cid : List.of("vp0", "vp1")) {
            Part foto = parteConCid(correo, cid);
            assertThat(foto).as("la foto %s viaja dentro del mensaje", cid).isNotNull();
            assertThat(disposicionDe(foto)).isEqualTo(Part.INLINE);
            assertThat(bytesDe(foto)).isNotEmpty();
        }
    }

    /**
     * El correo ya enviado es lo que espacia los envíos: el barrido corre a diario, pero a cada persona le
     * llega uno cada tres días. Sin esto, el recordatorio saldría todos los días.
     */
    @Test
    @DisplayName("el mismo usuario no recibe dos recordatorios dentro de la misma ventana")
    void noSeRepiteElRecordatorioDentroDeLaVentana() {
        visitar(token, camiseta);

        assertThat(digestService.sendDigests()).isEqualTo(1);
        assertThat(digestService.sendDigests()).as("segunda pasada del mismo día").isZero();

        despacharCola();
        assertThat(correosPara(CORREO)).hasSize(1);
    }

    /**
     * El producto se retira entre la visita y el envío. Como no queda nada que enseñar, no sale un correo
     * con la cuadrícula vacía — y, sobre todo, el barrido no revienta.
     */
    @Test
    @DisplayName("un producto retirado entre la visita y el envío no genera correo ni rompe el barrido")
    void unProductoRetiradoNoRompeElEnvio() {
        visitar(token, camiseta);
        borrarProducto(camiseta);

        assertThat(digestService.sendDigests()).isZero();

        despacharCola();
        assertThat(correosPara(CORREO)).isEmpty();
    }

    @Test
    @DisplayName("quien se dio de baja de marketing no recibe el recordatorio")
    void elOptOutDeMarketingLoDejaFuera() {
        visitar(token, camiseta);
        jdbcTemplate.update("UPDATE users SET marketing_opt_out = true WHERE id = ?", userId);

        assertThat(digestService.sendDigests()).isZero();

        despacharCola();
        assertThat(correosPara(CORREO)).isEmpty();
    }

    /* ============================== Retención ============================== */

    /** Los 90 días acordados: lo más viejo se va, lo reciente se queda. */
    @Test
    @DisplayName("la purga borra lo que supera los 90 días y respeta lo reciente")
    void laPurgaRespetaLoReciente() {
        visitar(token, camiseta);
        visitar(token, pantalon);
        envejecerVisita(userId, camiseta, 100);

        assertThat(historyService.purgeExpired()).isEqualTo(1);

        assertThat(titulosDelHistorial(token)).containsExactly("Pantalón");
    }

    /* ============================== Utilidades ============================== */

    private void visitar(String tokenUsuario, UUID productId) {
        client.post().uri(VIEWS + "/" + productId).header(HttpHeaders.AUTHORIZATION, bearer(tokenUsuario))
                .exchange().expectStatus().isOk();
    }

    /** Títulos del historial, en el orden en que los devuelve la API (visita más reciente primero). */
    @SuppressWarnings("unchecked")
    private List<String> titulosDelHistorial(String tokenUsuario) {
        Map<String, Object> pagina = client.get().uri(VIEWS + "?lang=es")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenUsuario)).exchange().expectStatus().isOk()
                .expectBody(PAGINA).returnResult().getResponseBody();
        List<Map<String, Object>> items = (List<Map<String, Object>>) pagina.get("items");
        return items.stream().map(i -> String.valueOf(i.get("title"))).toList();
    }

    private int filasEnBd(UUID usuario) {
        Integer total = jdbcTemplate.queryForObject("SELECT count(*) FROM product_view WHERE user_id = ?",
                Integer.class, usuario);
        return total == null ? 0 : total;
    }

    private int visitasContadas(UUID usuario, UUID producto) {
        Integer veces = jdbcTemplate.queryForObject(
                "SELECT view_count FROM product_view WHERE user_id = ? AND product_id = ?", Integer.class,
                usuario, producto);
        return veces == null ? 0 : veces;
    }

    /** Retrasa TODAS las visitas del usuario los días indicados (para salir de la ventana o caducarlas). */
    private void envejecerVisitas(UUID usuario, int dias) {
        jdbcTemplate.update("UPDATE product_view SET viewed_at = now() - make_interval(days => ?) "
                + "WHERE user_id = ?", dias, usuario);
    }

    private void envejecerVisita(UUID usuario, UUID producto, int dias) {
        jdbcTemplate.update("UPDATE product_view SET viewed_at = now() - make_interval(days => ?) "
                + "WHERE user_id = ? AND product_id = ?", dias, usuario, producto);
    }

    private void borrarProducto(UUID producto) {
        jdbcTemplate.update("DELETE FROM product_image WHERE product_id = ?", producto);
        jdbcTemplate.update("DELETE FROM product_translation WHERE product_id = ?", producto);
        jdbcTemplate.update("DELETE FROM product WHERE id = ?", producto);
    }

    private UUID crearUsuario(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, role, active, language, country, created_at,"
                + " updated_at) VALUES (?, ?, 'USER', true, 'es', 'ES', now(), now())", id, email);
        return id;
    }

    private UUID crearProducto(String slug, String titulo) {
        UUID id = UUID.randomUUID();
        String sufijo = id.toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO product (id, slug, external_id, source, title_zh, status, moq,"
                + " base_price, currency, weight_grams, created_at, updated_at)"
                + " VALUES (?, ?, ?, 'TEST', ?, 'ACTIVE', 1, 10.0000, 'CNY', 500, now(), now())",
                id, slug + "-" + sufijo, "ext-" + sufijo, titulo);
        jdbcTemplate.update("INSERT INTO product_translation (id, product_id, language, title, description) "
                + "VALUES (gen_random_uuid(), ?, 'es', ?, ?)", id, titulo, titulo + " — descripción");
        jdbcTemplate.update("INSERT INTO product_image (id, product_id, position, role, source_url, cdn_url) "
                + "VALUES (gen_random_uuid(), ?, 0, 'MAIN', ?, ?)", id,
                "https://origen.test/" + slug + ".jpg", "https://cdn.test/" + slug + ".jpg");
        return id;
    }

    /**
     * Divisas mínimas para que el catálogo sepa convertir. {@code cleanAllTables} vacía también
     * {@code currency_rate}, así que sin sembrarlas la lectura de cualquier precio en yuanes responde 404.
     */
    private void seedDivisas() {
        insertarDivisa("USD", "US Dollar", "$", null, "en-US", "1.00");
        insertarDivisa("EUR", "Euro", "€", "ES", "es-ES", "0.92");
        insertarDivisa("CNY", "Chinese Yuan", "¥", "CN", "zh-CN", "7.24");
        // La caché de tasas vive en la JVM con ventana propia: sin invalidarla seguiría sirviendo lo que
        // había antes del TRUNCATE.
        vaciarCaches();
    }

    private void insertarDivisa(String codigo, String nombre, String simbolo, String pais, String locale,
            String tasa) {
        jdbcTemplate.update("""
                INSERT INTO currency_rate (id, code, name, symbol, country_code, locale, rate_vs_usd, active,
                                           last_synced_at, created_at, updated_at)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, CAST(? AS NUMERIC), true, now(), now(), now())
                ON CONFLICT (code) DO NOTHING
                """, codigo, nombre, simbolo, pais, locale, tasa);
    }
}
