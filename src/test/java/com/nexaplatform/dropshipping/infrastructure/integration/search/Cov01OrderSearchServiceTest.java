package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reglas de la lectura paginada del índice de pedidos: cómo se arma la consulta, qué se descarta y
 * cuándo hay que caer a la base de datos.
 */
@ExtendWith(MockitoExtension.class)
class Cov01OrderSearchServiceTest {

    private static final UUID ID_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ID_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    HttpClient httpClient;

    ObjectMapper objectMapper;
    OrderSearchService service;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        // El servicio se construye su propio HttpClient: se sustituye por el doble vía reflexión.
        service = new OrderSearchService(objectMapper, "http://opensearch.test:9400/", "orders");
        Field f = OrderSearchService.class.getDeclaredField("httpClient");
        f.setAccessible(true);
        f.set(service, httpClient);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status, String body) {
        // Answer en lugar de when(): el helper se invoca DENTRO de when(send()).thenReturn(...) y un
        // stubbing anidado dispararía UnfinishedStubbingException.
        return (HttpResponse<String>) mock(HttpResponse.class, invocation -> {
            String m = invocation.getMethod().getName();
            if ("statusCode".equals(m)) {
                return status;
            }
            if ("body".equals(m)) {
                return body;
            }
            return null;
        });
    }

    /** Cuerpo JSON realmente enviado, leído del publicador de la petición. */
    private static String bodyOf(HttpRequest req) throws InterruptedException {
        StringBuilder sb = new StringBuilder();
        CountDownLatch done = new CountDownLatch(1);
        req.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                sb.append(StandardCharsets.UTF_8.decode(item));
            }

            @Override
            public void onError(Throwable throwable) {
                done.countDown();
            }

            @Override
            public void onComplete() {
                done.countDown();
            }
        });
        done.await(5, TimeUnit.SECONDS);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String sentBody() throws Exception {
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        return bodyOf(captor.getValue());
    }

    @SuppressWarnings("unchecked")
    private void stubOk(String body) throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response(200, body));
    }

    private static String hitsBody(long total, String... sources) {
        return "{\"hits\":{\"total\":{\"value\":" + total + "},\"hits\":[" + String.join(",", sources) + "]}}";
    }

    /* ============ construcción de la consulta ============ */

    @Test
    void laUrlDelIndiceSeArmaSinBarraDuplicadaYConElPrimerNodoDeLaLista() throws Exception {
        // La propiedad admite una lista de nodos separada por comas y puede venir con barra final.
        OrderSearchService multi = new OrderSearchService(objectMapper, " http://a.test:9400/ , http://b.test:9400 ",
                "pedidos");
        Field f = OrderSearchService.class.getDeclaredField("httpClient");
        f.setAccessible(true);
        f.set(multi, httpClient);
        stubOk(hitsBody(0));

        multi.pageIds(null, null, 0, 10);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(captor.getValue().uri()).hasToString("http://a.test:9400/pedidos/_search");
    }

    @Test
    void sinFiltrosSePidenTodosLosPedidosDelMasNuevoAlMasViejo() throws Exception {
        stubOk(hitsBody(0));

        service.pageIds(null, null, 0, 20);

        // El orden es la garantía de la pantalla: el admin espera ver arriba lo que acaba de entrar.
        // Y sin track_total_hits OpenSearch corta el conteo en 10.000, con lo que el paginador mentiría.
        assertThat(sentBody()).contains("\"query\":{\"match_all\":{}}")
                .contains("\"sort\":[{\"sortTs\":{\"order\":\"desc\"}}]").contains("\"track_total_hits\":true");
    }

    @Test
    void elEstadoSeNormalizaAMayusculasYSeFiltraComoTerminoExacto() throws Exception {
        stubOk(hitsBody(0));

        service.pageIds("  paid  ", null, 0, 20);

        assertThat(sentBody()).contains("{\"term\":{\"status\":\"PAID\"}}");
    }

    @Test
    void unEstadoEnBlancoNoAnadeFiltro() throws Exception {
        stubOk(hitsBody(0));

        service.pageIds("   ", "   ", 0, 20);

        assertThat(sentBody()).contains("\"query\":{\"match_all\":{}}");
    }

    @Test
    void unaComillaEnLaBusquedaNoRompeElJsonDeLaConsulta() throws Exception {
        // Concatenar el texto en crudo generaba un cuerpo inválido y OpenSearch devolvía 400: la pantalla
        // se quedaba sin resultados en cuanto alguien buscaba con comillas.
        stubOk(hitsBody(0));

        service.pageIds(null, "cliente \"raro\" \\ ", 0, 20);

        String body = sentBody();
        JsonNode parsed = objectMapper.readTree(body);
        assertThat(parsed.at("/query/bool/must/0/multi_match/query").asText()).isEqualTo("cliente \"raro\" \\");
    }

    @Test
    void elTextoLibreBuscaSobreNumeroPedidoIdExternoYNombreDeEnvio() throws Exception {
        stubOk(hitsBody(0));

        service.pageIds(null, "NX-100", 0, 20);

        assertThat(sentBody()).contains("\"fields\":[\"orderNumber\",\"externalOrderId\",\"shippingName\"]");
    }

    @Test
    void estadoYTextoSeCombinanComoDosCondicionesObligatorias() throws Exception {
        stubOk(hitsBody(0));

        service.pageIds("SHIPPED", "NX-100", 0, 20);

        JsonNode must = objectMapper.readTree(sentBody()).at("/query/bool/must");
        assertThat(must.size()).isEqualTo(2);
    }

    @Test
    void elDesplazamientoSaleDeLaPaginaPorElTamano() throws Exception {
        stubOk(hitsBody(0));

        service.pageIds(null, null, 2, 25);

        assertThat(sentBody()).contains("\"from\":50").contains("\"size\":25");
    }

    @Test
    void unaPaginaNegativaSeTrataComoLaPrimeraYNoComoUnDesplazamientoInvalido() throws Exception {
        // Un "from" negativo lo rechaza OpenSearch con 400 y la pantalla quedaría vacía.
        stubOk(hitsBody(0));

        service.pageIds(null, null, -5, 20);

        assertThat(sentBody()).contains("\"from\":0");
    }

    /* ============ lectura de la respuesta ============ */

    @Test
    void devuelveLosIdsEnElOrdenDelIndiceConSuTotal() throws Exception {
        stubOk(hitsBody(137, "{\"_source\":{\"id\":\"" + ID_1 + "\"}}", "{\"_source\":{\"id\":\"" + ID_2 + "\"}}"));

        Optional<OrderSearchService.IdPage> page = service.pageIds(null, null, 0, 20);

        assertThat(page).isPresent();
        assertThat(page.get().ids()).containsExactly(ID_1, ID_2);
        assertThat(page.get().total()).isEqualTo(137L);
    }

    @Test
    void siElHitNoTraeSourceSeUsaElIdentificadorDelDocumento() throws Exception {
        stubOk(hitsBody(1, "{\"_id\":\"" + ID_1 + "\"}"));

        Optional<OrderSearchService.IdPage> page = service.pageIds(null, null, 0, 20);

        assertThat(page).isPresent();
        assertThat(page.get().ids()).containsExactly(ID_1);
    }

    @Test
    void unIdIlegibleSeDescartaSinTumbarElRestoDeLaPagina() throws Exception {
        stubOk(hitsBody(2, "{\"_source\":{\"id\":\"no-es-un-uuid\"}}", "{\"_source\":{\"id\":\"" + ID_2 + "\"}}"));

        Optional<OrderSearchService.IdPage> page = service.pageIds(null, null, 0, 20);

        assertThat(page).isPresent();
        assertThat(page.get().ids()).containsExactly(ID_2);
    }

    @Test
    void siNingunIdEsLegibleSeCaeALaBaseDeDatos() throws Exception {
        // Devolver una página vacía con total>0 dejaría al admin con un listado en blanco y sin explicación.
        stubOk(hitsBody(3, "{\"_source\":{\"id\":\"basura\"}}"));

        assertThat(service.pageIds(null, null, 0, 20)).isEmpty();
    }

    @Test
    void unIndiceVacioSeTrataComoAusenciaDeIndiceYSeCaeALaBaseDeDatos() throws Exception {
        // Un índice recién creado (o aún sin poblar) no puede ocultar los pedidos que sí están en la BD.
        stubOk(hitsBody(0));

        assertThat(service.pageIds(null, null, 0, 20)).isEmpty();
    }

    @Test
    void unaRespuestaDeErrorDelIndiceSeCaeALaBaseDeDatos() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response(503, null));

        assertThat(service.pageIds(null, null, 0, 20)).isEmpty();
    }

    @Test
    void unFalloDeRedSeCaeALaBaseDeDatosEnVezDePropagarse() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("opensearch caído"));

        assertThat(service.pageIds(null, null, 0, 20)).isEmpty();
    }

    @Test
    void unaRespuestaIlegibleSeCaeALaBaseDeDatos() throws Exception {
        stubOk("no soy json");

        assertThat(service.pageIds(null, null, 0, 20)).isEmpty();
    }

    @Test
    void unaInterrupcionRestauraLaBanderaDelHilo() throws Exception {
        // Tragarse la interrupción deja al pool sin enterarse de que le han pedido parar.
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("parada"));

        Optional<OrderSearchService.IdPage> page = service.pageIds(null, null, 0, 20);

        assertThat(page).isEmpty();
        // Thread.interrupted() comprueba y limpia: la bandera no se filtra a los demás tests.
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void laPaginaVaciaDeHitsConTotalPositivoTambienCaeALaBaseDeDatos() throws Exception {
        stubOk("{\"hits\":{\"total\":{\"value\":9},\"hits\":[]}}");

        assertThat(service.pageIds(null, null, 5, 20)).isEmpty();
    }
}
