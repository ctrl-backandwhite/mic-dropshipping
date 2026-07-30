package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lectura del listado de wallets desde el índice de OpenSearch. Es un ATAJO de paginación/filtro: el
 * dinero se relee siempre de la base de datos, así que cualquier problema del índice tiene que
 * degradar a {@link Optional#empty()} y nunca propagar ni inventar resultados.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov07WalletSearchServiceTest {

    @Mock
    HttpClient httpClient;

    private WalletSearchService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new WalletSearchService(new ObjectMapper(), "http://nodo-1:9400///,http://nodo-2:9400", "wallets");
        // El cliente HTTP se crea dentro de la clase (campo final): se sustituye por el doble.
        Field field = WalletSearchService.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(service, httpClient);
    }

    @AfterEach
    void tearDown() {
        // Un test deja el hilo marcado como interrumpido a propósito: hay que limpiarlo.
        Thread.interrupted();
    }

    @Test
    void laUrlApuntaAlPrimerNodoSinBarrasFinales() {
        // Varios nodos separados por coma y con barras de más es lo que llega por configuración.
        assertThat(ReflectionTestUtils.getField(service, "searchUrl"))
                .isEqualTo("http://nodo-1:9400/wallets/_search");
    }

    /* ==================== degradación a base de datos ==================== */

    @Test
    void unaRespuestaDeErrorDelIndiceCaeALaBaseDeDatos() throws Exception {
        respond(503, "{}");

        assertThat(service.pageIds(null, null, null, 0, 10)).isEmpty();
    }

    @Test
    void unIndiceSinResultadosCaeALaBaseDeDatos() throws Exception {
        respond(200, "{\"hits\":{\"total\":{\"value\":0},\"hits\":[]}}");

        // Total 0 puede significar "índice aún no poblado": mejor releer de la BD que enseñar vacío.
        assertThat(service.pageIds(null, null, null, 0, 10)).isEmpty();
    }

    @Test
    void unFalloDeRedCaeALaBaseDeDatos() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("nodo caído"));

        assertThat(service.pageIds(null, null, null, 0, 10)).isEmpty();
    }

    @Test
    void unaRespuestaIlegibleCaeALaBaseDeDatos() throws Exception {
        respond(200, "esto-no-es-json");

        assertThat(service.pageIds(null, null, null, 0, 10)).isEmpty();
    }

    @Test
    void unaInterrupcionSeReemitePeroTambienCaeALaBaseDeDatos() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("parada"));

        assertThat(service.pageIds(null, null, null, 0, 10)).isEmpty();
        // Tragarse la interrupción dejaría al pool sin enterarse de que le han pedido parar.
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void siNingunIdentificadorEsValidoSeCaeALaBaseDeDatos() throws Exception {
        respond(200, "{\"hits\":{\"total\":{\"value\":2},\"hits\":["
                + "{\"_id\":\"no-es-uuid\"},{\"_id\":\"  \"}]}}");

        assertThat(service.pageIds(null, null, null, 0, 10)).isEmpty();
    }

    /* ==================== página de identificadores ==================== */

    @Test
    void losIdentificadoresSalenDelSourceYSiFaltaDelIdDelDocumento() throws Exception {
        UUID conSource = UUID.randomUUID();
        UUID soloId = UUID.randomUUID();
        respond(200, "{\"hits\":{\"total\":{\"value\":42},\"hits\":["
                + "{\"_id\":\"otro\",\"_source\":{\"id\":\"" + conSource + "\"}},"
                + "{\"_id\":\"" + soloId + "\"}]}}");

        Optional<WalletSearchService.IdPage> page = service.pageIds(null, null, null, 0, 10);

        assertThat(page).isPresent();
        assertThat(page.get().ids()).containsExactly(conSource, soloId);
        // El total es el del filtro completo, no el de la página: es lo que pagina el listado.
        assertThat(page.get().total()).isEqualTo(42L);
    }

    @Test
    void losIdentificadoresQueNoSonUuidSeDescartanSinTumbarLaPagina() throws Exception {
        UUID valido = UUID.randomUUID();
        respond(200, "{\"hits\":{\"total\":{\"value\":3},\"hits\":["
                + "{\"_id\":\"basura\"},{\"_id\":\"" + valido + "\"}]}}");

        assertThat(service.pageIds(null, null, null, 0, 10)).get()
                .extracting(WalletSearchService.IdPage::ids).isEqualTo(List.of(valido));
    }

    /* ==================== consulta enviada al índice ==================== */

    @Test
    void sinFiltrosSePidenTodosLosDocumentosOrdenadosPorFecha() throws Exception {
        respond(200, "{\"hits\":{\"total\":{\"value\":0},\"hits\":[]}}");

        service.pageIds("  ", "  ", null, 3, 20);

        String body = sentBody();
        assertThat(body).contains("\"query\":{\"match_all\":{}}")
                .contains("\"from\":60")
                .contains("\"size\":20")
                .contains("\"_source\":[\"id\"]")
                .contains("\"track_total_hits\":true")
                .contains("{\"createdAt\":{\"order\":\"desc\"}}");
    }

    @Test
    void unaPaginaNegativaSeTrataComoLaPrimera() throws Exception {
        respond(200, "{\"hits\":{\"total\":{\"value\":0},\"hits\":[]}}");

        service.pageIds(null, null, null, -5, 20);

        // Un "from" negativo haría que OpenSearch devolviera 400 y se perdiera el listado entero.
        assertThat(sentBody()).contains("\"from\":0");
    }

    @Test
    void elEstadoYLaDivisaSeExigenEnMayusculasYALaVez() throws Exception {
        respond(200, "{\"hits\":{\"total\":{\"value\":0},\"hits\":[]}}");

        service.pageIds(null, " activa ", "eur", 0, 10);

        String body = sentBody();
        assertThat(body).contains("{\"term\":{\"status\":\"ACTIVA\"}}")
                .contains("{\"term\":{\"currency\":\"EUR\"}}")
                .contains("\"bool\":{\"must\":[");
    }

    @Test
    void elTextoDeBusquedaViajaEscapadoYSoloContraCorreoYNombre() throws Exception {
        respond(200, "{\"hits\":{\"total\":{\"value\":0},\"hits\":[]}}");

        service.pageIds("ana \"la\" jefa", null, null, 0, 10);

        // Sin escapar, unas comillas en el buscador romperían el JSON de la petición (400 del índice).
        assertThat(sentBody()).contains("\"multi_match\":{\"query\":\"ana \\\"la\\\" jefa\"")
                .contains("\"fields\":[\"userEmail\",\"userName\"]");
    }

    @Test
    void laPeticionVaAlaUrlDelIndiceComoJson() throws Exception {
        respond(200, "{\"hits\":{\"total\":{\"value\":0},\"hits\":[]}}");

        service.pageIds(null, null, null, 0, 10);

        HttpRequest request = sentRequest();
        assertThat(request.uri()).hasToString("http://nodo-1:9400/wallets/_search");
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.headers().firstValue("Content-Type")).contains("application/json");
    }

    /* ==================== helpers ==================== */

    @SuppressWarnings("unchecked")
    private void respond(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }

    @SuppressWarnings("unchecked")
    private HttpRequest sentRequest() throws Exception {
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        return captor.getValue();
    }

    private String sentBody() throws Exception {
        return bodyOf(sentRequest());
    }

    /** Lee el cuerpo de la petición: {@code BodyPublishers.ofString} lo entrega de forma síncrona. */
    private static String bodyOf(HttpRequest request) {
        StringBuilder text = new StringBuilder();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                text.append(StandardCharsets.UTF_8.decode(item));
            }

            @Override
            public void onError(Throwable throwable) {
                throw new IllegalStateException(throwable);
            }

            @Override
            public void onComplete() {
                // nada que hacer: el texto ya está completo
            }
        });
        return text.toString();
    }
}
