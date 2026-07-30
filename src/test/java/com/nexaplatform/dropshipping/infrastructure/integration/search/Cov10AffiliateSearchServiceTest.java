package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.infrastructure.integration.search.AffiliateSearchService.IdPage;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lectura del listado de afiliados desde el índice. Es un camino "best-effort": cualquier fallo debe
 * devolver vacío para que el controlador caiga al listado de base de datos, nunca propagar el error. Los
 * tests levantan un OpenSearch de mentira para poder inspeccionar la consulta que se envía de verdad.
 */
class Cov10AffiliateSearchServiceTest {

    private HttpServer server;
    private final AtomicReference<String> ultimaConsulta = new AtomicReference<>();
    private final AtomicInteger estado = new AtomicInteger(200);
    private final AtomicReference<String> respuesta = new AtomicReference<>("{}");
    private final ObjectMapper objectMapper = new ObjectMapper();

    private AffiliateSearchService service;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/afiliados/_search", exchange -> {
            ultimaConsulta.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = respuesta.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(estado.get(), out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        // La barra final de la URI configurada se limpia: si no, la ruta saldría con doble barra y 404.
        service = new AffiliateSearchService(objectMapper,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/,http://otro:9400", "afiliados");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /* ---------- construcción de la consulta ---------- */

    @Test
    void sinFiltrosSePidenTodosLosDocumentosOrdenadosDelMasNuevoAlMasViejo() throws Exception {
        responde(1, UUID.randomUUID());

        service.pageIds(null, null, 0, 25);

        JsonNode q = consulta();
        assertThat(q.path("query").has("match_all")).isTrue();
        assertThat(q.path("sort").get(0).path("createdAt").path("order").asText()).isEqualTo("desc");
        // Sin track_total_hits OpenSearch tapa el total en 10.000 y el paginador del panel mentiría.
        assertThat(q.path("track_total_hits").asBoolean()).isTrue();
        assertThat(q.path("size").asInt()).isEqualTo(25);
    }

    @Test
    void laPaginaSeTraduceAUnDesplazamientoYNuncaEsNegativa() throws Exception {
        responde(1, UUID.randomUUID());

        service.pageIds(null, null, 2, 10);
        assertThat(consulta().path("from").asInt()).isEqualTo(20);

        // Una página negativa (parámetro manipulado) daría un from negativo y OpenSearch respondería 400.
        service.pageIds(null, null, -3, 10);
        assertThat(consulta().path("from").asInt()).isZero();
    }

    @Test
    void elFiltroDeEstadoViajaEnMayusculasComoTerminoExacto() throws Exception {
        responde(1, UUID.randomUUID());

        service.pageIds(null, " activo ", 0, 25);

        JsonNode must = consulta().path("query").path("bool").path("must");
        assertThat(must.size()).isEqualTo(1);
        assertThat(must.get(0).path("term").path("status").asText()).isEqualTo("ACTIVO");
    }

    @Test
    void elTextoDeBusquedaSeEscapaParaNoRomperLaPeticion() throws Exception {
        responde(1, UUID.randomUUID());

        // Con concatenación directa, unas comillas en el buscador dejarían la consulta ilegible (400).
        service.pageIds(" \"Ana\" \\ x ", null, 0, 25);

        JsonNode multi = consulta().path("query").path("bool").path("must").get(0).path("multi_match");
        assertThat(multi.path("query").asText()).isEqualTo("\"Ana\" \\ x");
        assertThat(multi.path("fields").size()).isEqualTo(3);
    }

    @Test
    void losDosFiltrosSeExigenALaVez() throws Exception {
        responde(1, UUID.randomUUID());

        service.pageIds("ana", "ACTIVE", 0, 25);

        assertThat(consulta().path("query").path("bool").path("must").size()).isEqualTo(2);
    }

    @Test
    void unTextoEnBlancoNoCuentaComoFiltro() throws Exception {
        responde(1, UUID.randomUUID());

        service.pageIds("   ", "   ", 0, 25);

        assertThat(consulta().path("query").has("match_all")).isTrue();
    }

    /* ---------- lectura de la respuesta ---------- */

    @Test
    void elIdSaleDeSourceYSiElIndiceNoLoGuardoDelIdentificadorDelDocumento() {
        UUID conSource = UUID.randomUUID();
        UUID soloDocId = UUID.randomUUID();
        respuesta.set("{\"hits\":{\"total\":{\"value\":2},\"hits\":["
                + "{\"_id\":\"otro\",\"_source\":{\"id\":\"" + conSource + "\"}},"
                + "{\"_id\":\"" + soloDocId + "\",\"_source\":{}}]}}");

        Optional<IdPage> page = service.pageIds(null, null, 0, 25);

        assertThat(page).isPresent();
        assertThat(page.get().ids()).containsExactly(conSource, soloDocId);
        assertThat(page.get().total()).isEqualTo(2);
    }

    @Test
    void unIdCorruptoSeDescartaSinTirarLaPaginaEntera() {
        UUID bueno = UUID.randomUUID();
        respuesta.set("{\"hits\":{\"total\":{\"value\":3},\"hits\":["
                + "{\"_id\":\"no-es-un-uuid\",\"_source\":{}},"
                + "{\"_id\":\"x\",\"_source\":{\"id\":\"\"}},"
                + "{\"_id\":\"x\",\"_source\":{\"id\":\"" + bueno + "\"}}]}}");

        Optional<IdPage> page = service.pageIds(null, null, 0, 25);

        assertThat(page).isPresent();
        assertThat(page.get().ids()).containsExactly(bueno);
    }

    /* ---------- caídas al listado de base de datos ---------- */

    @Test
    void unFiltroSinResultadosSeDevuelveComoPaginaVaciaYNoComoIndiceCaido() {
        // Optional.empty() significa «el índice no ha contestado, tira de base de datos». Cero resultados
        // es una respuesta VÁLIDA —un filtro que no encaja con nada— y devolverla como vacío forzaba una
        // consulta a base de datos que tampoco iba a encontrar nada.
        respuesta.set("{\"hits\":{\"total\":{\"value\":0},\"hits\":[]}}");

        assertThat(service.pageIds(null, null, 0, 25))
                .hasValueSatisfying(p -> {
                    assertThat(p.ids()).isEmpty();
                    assertThat(p.total()).isZero();
                });
    }

    @Test
    void unaPaginaSinNingunIdValidoTambienCaeALaBaseDeDatos() {
        respuesta.set("{\"hits\":{\"total\":{\"value\":5},\"hits\":["
                + "{\"_id\":\"no-es-un-uuid\",\"_source\":{}}]}}");

        assertThat(service.pageIds(null, null, 0, 25)).isEmpty();
    }

    @Test
    void unErrorHttpDelIndiceNoSePropagaAlPanel() {
        estado.set(503);
        respuesta.set("{\"error\":\"unavailable\"}");

        assertThat(service.pageIds(null, null, 0, 25)).isEmpty();
    }

    @Test
    void unIndiceInalcanzableNoRompeElListadoDeAfiliados() {
        AffiliateSearchService caido = new AffiliateSearchService(objectMapper, "http://127.0.0.1:1", "afiliados");

        assertThat(caido.pageIds("ana", "ACTIVE", 0, 25)).isEmpty();
    }

    /* ---------- utilidades ---------- */

    private void responde(int total, UUID id) {
        respuesta.set("{\"hits\":{\"total\":{\"value\":" + total + "},\"hits\":[{\"_id\":\"" + id
                + "\",\"_source\":{\"id\":\"" + id + "\"}}]}}");
    }

    private JsonNode consulta() throws Exception {
        return objectMapper.readTree(ultimaConsulta.get());
    }
}
