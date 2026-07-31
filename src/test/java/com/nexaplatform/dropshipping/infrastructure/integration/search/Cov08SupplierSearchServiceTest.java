package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.infrastructure.integration.search.SupplierSearchService.IndexedPage;
import com.nexaplatform.dropshipping.infrastructure.integration.search.SupplierSearchService.IndexedSupplier;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lectura del catálogo de proveedores desde el índice de OpenSearch.
 *
 * <p>Es una ruta best-effort: el listado del escaparate tiene que salir aunque el índice esté vacío,
 * devuelva un error o no haya nadie escuchando. La regla que se fija es siempre la misma —cualquier
 * anomalía se convierte en {@link Optional#empty()} para que el llamante caiga a la base de datos— más
 * el orden y el filtrado que el índice no sabe hacer por sí solo.
 *
 * <p>Se levanta un servidor HTTP de verdad (puerto efímero) porque el cliente HTTP lo crea el propio
 * servicio y no se puede sustituir por un doble.
 */
class Cov08SupplierSearchServiceTest {

    private HttpServer server;
    private SupplierSearchService service;

    /** Última petición recibida: sirve para comprobar la consulta que se manda al índice. */
    private String lastRequestBody;
    private int responseStatus = 200;
    private String responseBody = "{}";

    private static final UUID ID_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ID_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        service = new SupplierSearchService(new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/", "suppliers");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            lastRequestBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    private static String hit(UUID id, String name, String extra) {
        return "{\"_source\":{\"id\":\"" + id + "\",\"name\":\"" + name + "\"" + extra + "}}";
    }

    private void indexResponds(long total, String... hits) {
        responseBody = "{\"hits\":{\"total\":{\"value\":" + total + "},\"hits\":[" + String.join(",", hits) + "]}}";
    }

    // ─────────────────────── página del listado ───────────────────────

    @Test
    void unaPaginaDelIndiceSeConvierteEnFilasDelListado() {
        indexResponds(2, hit(ID_1, "Shenzhen Trading",
                ",\"externalId\":\"E-1\",\"nameZh\":\"深圳\",\"country\":\"CN\",\"city\":\"Shenzhen\","
                        + "\"rating\":4.5,\"yearsActive\":7,\"verified\":true,\"trustPass\":true,"
                        + "\"productCount\":320"));

        IndexedPage page = service.pageFromIndex(null, null, null, 0, 20).orElseThrow();

        assertThat(page.total()).isEqualTo(2);
        IndexedSupplier row = page.items().get(0);
        assertThat(row.id()).isEqualTo(ID_1);
        assertThat(row.externalId()).isEqualTo("E-1");
        assertThat(row.rating()).isEqualByComparingTo(new BigDecimal("4.5"));
        assertThat(row.yearsActive()).isEqualTo(7);
        assertThat(row.verified()).isTrue();
        assertThat(row.trustPass()).isTrue();
        assertThat(row.productCount()).isEqualTo(320L);
    }

    @Test
    void unProveedorSinValoracionNiAntiguedadNoInventaCeros() {
        // Un 0,0 de valoración en la ficha es una afirmación falsa: "no valorado" no es "valorado con 0".
        indexResponds(1, hit(ID_1, "Sin datos", ""));

        IndexedSupplier row = service.pageFromIndex(null, null, null, 0, 20).orElseThrow().items().get(0);

        assertThat(row.rating()).isNull();
        assertThat(row.yearsActive()).isNull();
        assertThat(row.verified()).isFalse();
        assertThat(row.productCount()).isZero();
    }

    @Test
    void unIndiceVacioDevuelveNadaParaQueElLlamanteUseLaBaseDeDatos() {
        indexResponds(0);

        assertThat(service.pageFromIndex(null, null, null, 0, 20)).isEmpty();
    }

    @Test
    void unErrorDelIndiceNoRompeElListado() {
        responseStatus = 503;
        responseBody = "{\"error\":\"unavailable\"}";

        assertThat(service.pageFromIndex(null, null, null, 0, 20)).isEmpty();
    }

    @Test
    void unaRespuestaIlegibleNoRompeElListado() {
        responseBody = "esto no es json";

        assertThat(service.pageFromIndex(null, null, null, 0, 20)).isEmpty();
    }

    @Test
    void siElIndiceNoRespondeSeCaeALaBaseDeDatos() throws IOException {
        int puertoCerrado;
        try (ServerSocket libre = new ServerSocket(0)) {
            puertoCerrado = libre.getLocalPort();
        }
        SupplierSearchService sinIndice = new SupplierSearchService(new ObjectMapper(),
                "http://127.0.0.1:" + puertoCerrado, "suppliers");

        assertThat(sinIndice.pageFromIndex(null, null, null, 0, 20)).isEmpty();
        assertThat(sinIndice.listFromIndex(null)).isEmpty();
    }

    @Test
    void losTresFiltrosViajanJuntosEnLaConsulta() {
        // Si uno se perdiera, el admin vería proveedores que había filtrado fuera.
        indexResponds(1, hit(ID_1, "Uno", ""));

        service.pageFromIndex(" acero ", " CN ", Boolean.TRUE, 0, 20);

        assertThat(lastRequestBody).contains("multi_match").contains("\"acero\"")
                .contains("\"term\":{\"country\":\"CN\"}").contains("\"term\":{\"verified\":true}");
    }

    @Test
    void sinFiltrosSePideTodoElIndice() {
        indexResponds(1, hit(ID_1, "Uno", ""));

        service.pageFromIndex("   ", "  ", null, 0, 20);

        assertThat(lastRequestBody).contains("match_all").doesNotContain("multi_match");
    }

    @Test
    void unNumeroDePaginaNegativoNoSeConvierteEnUnDesplazamientoNegativo() {
        // Un "from" negativo lo rechaza OpenSearch entero: la página quedaría en blanco.
        indexResponds(1, hit(ID_1, "Uno", ""));

        service.pageFromIndex(null, null, null, -3, 20);

        assertThat(lastRequestBody).contains("\"from\":0");
    }

    @Test
    void lasPaginasSiguientesDesplazanPorElTamanoDePagina() {
        indexResponds(1, hit(ID_1, "Uno", ""));

        service.pageFromIndex(null, null, null, 2, 30);

        assertThat(lastRequestBody).contains("\"from\":60").contains("\"size\":30");
    }

    // ─────────────────────── listado completo ───────────────────────

    @Test
    void elListadoCompletoSeOrdenaPorNombreIgnorandoMayusculas() {
        // El campo name es texto y OpenSearch no lo sabe ordenar; si no se ordenara aquí, el desplegable
        // saldría en el orden arbitrario del índice.
        indexResponds(3, hit(ID_1, "zeta", ""), hit(ID_2, "Alfa", ""),
                hit(UUID.fromString("33333333-3333-3333-3333-333333333333"), "beta", ""));

        List<IndexedSupplier> rows = service.listFromIndex(null).orElseThrow();

        assertThat(rows).extracting(IndexedSupplier::name).containsExactly("Alfa", "beta", "zeta");
    }

    @Test
    void unaFilaSinIdentificadorValidoSeDescarta() {
        // Sin id no se puede enlazar la ficha; meterla en la lista daría un enlace roto.
        indexResponds(2, "{\"_source\":{\"id\":\"no-es-un-uuid\",\"name\":\"Rota\"}}", hit(ID_1, "Buena", ""));

        List<IndexedSupplier> rows = service.listFromIndex(null).orElseThrow();

        assertThat(rows).extracting(IndexedSupplier::name).containsExactly("Buena");
    }

    @Test
    void unListadoSinResultadosDejaDecidirALaBaseDeDatos() {
        responseBody = "{\"hits\":{\"hits\":[]}}";

        assertThat(service.listFromIndex(null)).isEmpty();
    }

    @Test
    void elFiltroDeTextoDelListadoBuscaEnNombreYUbicacion() {
        indexResponds(1, hit(ID_1, "Uno", ""));

        service.listFromIndex("  textil  ");

        assertThat(lastRequestBody).contains("\"textil\"").contains("nameZh").contains("city");
    }

    @Test
    void unErrorDelIndiceTampocoRompeElListadoCompleto() {
        responseStatus = 500;

        assertThat(service.listFromIndex(null)).isEmpty();
    }
}
