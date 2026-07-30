package com.nexaplatform.dropshipping.infrastructure.integration.shop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.domain.model.ShopConnection;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Publicación real de un producto en Shopify. Lo que se fija aquí es que sin credenciales o sin un
 * handle válido NO se sale a la red, que el error de Shopify llega al panel en lugar de fallar en
 * silencio, y qué payload se manda.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov07ShopifyConnectorTest {

    @Mock
    HttpClient httpClient;

    private ShopifyConnector connector;

    @BeforeEach
    void setUp() throws Exception {
        connector = new ShopifyConnector(new ObjectMapper());
        // El cliente HTTP se construye dentro de la clase (campo final): se sustituye por el doble.
        Field field = ShopifyConnector.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(connector, httpClient);
    }

    @AfterEach
    void tearDown() {
        // Un test provoca una interrupción a propósito: hay que limpiar la marca del hilo.
        Thread.interrupted();
    }

    @Test
    void elConectorSeAnunciaComoShopifyYDisponible() {
        assertThat(connector.platform()).isEqualTo("shopify");
        assertThat(connector.available()).isTrue();
    }

    /* ==================== validaciones previas ==================== */

    @Test
    void sinTokenDeAccesoNoSeSaleALaRed() throws Exception {
        ShopConnector.PushResult sinToken = connector.push(shop("mi-tienda.myshopify.com"), null, product());
        ShopConnector.PushResult tokenVacio = connector.push(shop("mi-tienda.myshopify.com"), "  ", product());

        assertThat(sinToken.ok()).isFalse();
        assertThat(sinToken.error()).contains("token de acceso");
        assertThat(tokenVacio.ok()).isFalse();
        verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void unHandleDeTiendaVacioSeRechazaAntesDeLlamar() throws Exception {
        assertThat(connector.push(shop(null), "tok", product()).ok()).isFalse();
        assertThat(connector.push(shop("  "), "tok", product()).ok()).isFalse();
        assertThat(connector.push(shop("https://"), "tok", product()).error())
                .contains("Handle de tienda inválido");
        verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @ParameterizedTest
    @CsvSource({
            "mi-tienda.myshopify.com,mi-tienda.myshopify.com",
            "mi-tienda,mi-tienda.myshopify.com",
            "https://mi-tienda.myshopify.com,mi-tienda.myshopify.com",
            "http://mi-tienda.myshopify.com/admin/api,mi-tienda.myshopify.com" })
    void elHandleSeNormalizaAlHostDeLaTienda(String handle, String host) throws Exception {
        respond(201, "{\"product\":{\"id\":1}}");

        connector.push(shop(handle), "tok", product());

        assertThat(sentRequest().uri().toString())
                .isEqualTo("https://" + host + "/admin/api/2024-10/products.json");
    }

    /* ==================== respuesta de Shopify ==================== */

    @Test
    void unaPublicacionCorrectaDevuelveElIdRemotoPrefijado() throws Exception {
        respond(201, "{\"product\":{\"id\":987654321}}");

        ShopConnector.PushResult result = connector.push(shop("mi-tienda"), "tok", product());

        assertThat(result.ok()).isTrue();
        assertThat(result.remoteProductId()).isEqualTo("shopify-987654321");
        assertThat(result.error()).isNull();
    }

    @Test
    void siShopifyNoDevuelveIdSeGuardaLaMarcaGenerica() throws Exception {
        respond(200, "{\"product\":{}}");

        ShopConnector.PushResult result = connector.push(shop("mi-tienda"), "tok", product());

        assertThat(result.ok()).isTrue();
        assertThat(result.remoteProductId()).isEqualTo("shopify");
    }

    @Test
    void unErrorDeShopifyLlegaAlPanelConSuCodigoYCuerpo() throws Exception {
        respond(422, "{\"errors\":{\"title\":[\"can't be blank\"]}}");

        ShopConnector.PushResult result = connector.push(shop("mi-tienda"), "tok", product());

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("422").contains("can't be blank");
    }

    @Test
    void unCuerpoDeErrorEnormeSeRecortaATrescientosCaracteres() throws Exception {
        respond(500, "x".repeat(1000));

        ShopConnector.PushResult result = connector.push(shop("mi-tienda"), "tok", product());

        // Sin recorte, un HTML de error de 1 MB acabaría entero en la columna del último error.
        assertThat(result.error()).hasSize("Shopify respondió 500: ".length() + 300);
    }

    @Test
    void unFalloDeRedSeReportaComoErrorDeConexion() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("host inalcanzable"));

        ShopConnector.PushResult result = connector.push(shop("mi-tienda"), "tok", product());

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("No se pudo conectar con Shopify").contains("host inalcanzable");
    }

    @Test
    void unaInterrupcionSeReemiteAlHilo() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("parada"));

        assertThat(connector.push(shop("mi-tienda"), "tok", product()).ok()).isFalse();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    /* ==================== payload ==================== */

    @Test
    void elProductoViajaConSuTituloPrecioYTokenEnLaCabecera() throws Exception {
        respond(201, "{\"product\":{\"id\":1}}");

        connector.push(shop("mi-tienda"), "shpat_secreto", product());

        HttpRequest request = sentRequest();
        assertThat(request.headers().firstValue("X-Shopify-Access-Token")).contains("shpat_secreto");
        assertThat(request.headers().firstValue("Content-Type")).contains("application/json");
        String body = bodyOf(request);
        assertThat(body).contains("\"title\":\"衬衫\"").contains("\"price\":\"19.90\"")
                .contains("\"vendor\":\"NX\"").contains("\"status\":\"active\"");
    }

    @Test
    void unProductoSinTituloChinoSePublicaConSuSlugYPrecioCero() throws Exception {
        respond(201, "{\"product\":{\"id\":1}}");
        ProductEntity bare = ProductEntity.builder().slug("camisa-lino").build();

        connector.push(shop("mi-tienda"), "tok", bare);

        String body = bodyOf(sentRequest());
        // Shopify rechaza un producto sin título: el slug es el último recurso para no perder la publicación.
        assertThat(body).contains("\"title\":\"camisa-lino\"").contains("\"price\":\"0\"")
                .contains("\"vendor\":\"\"").contains("\"body_html\":\"\"");
    }

    /* ==================== helpers ==================== */

    private static ShopConnection shop(String handle) {
        return ShopConnection.builder().id(UUID.randomUUID()).platform("shopify").shopHandle(handle).build();
    }

    private static ProductEntity product() {
        return ProductEntity.builder().slug("camisa-lino").titleZh("衬衫").descriptionZh("descripción")
                .brand("NX").basePrice(new BigDecimal("19.90")).build();
    }

    @SuppressWarnings("unchecked")
    private void respond(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }

    private HttpRequest sentRequest() throws Exception {
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        return captor.getValue();
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
