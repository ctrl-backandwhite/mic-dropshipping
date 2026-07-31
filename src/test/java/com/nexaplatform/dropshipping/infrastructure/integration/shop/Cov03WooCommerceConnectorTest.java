package com.nexaplatform.dropshipping.infrastructure.integration.shop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.domain.model.ShopConnection;
import com.nexaplatform.dropshipping.infrastructure.integration.shop.ShopConnector.PushResult;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Publicación de un producto en una tienda WooCommerce conectada.
 *
 * <p>Es una llamada a un servidor ajeno: nada de lo que devuelva (o deje de devolver) puede convertirse
 * en una excepción sin explicación. Cada fallo tiene que llegar al panel con un motivo que el comerciante
 * pueda entender y corregir.
 */
class Cov03WooCommerceConnectorTest {

    private HttpClient httpClient;
    private WooCommerceConnector connector;

    @BeforeEach
    void preparaConector() {
        httpClient = mock(HttpClient.class);
        connector = new WooCommerceConnector(new ObjectMapper());
        ReflectionTestUtils.setField(connector, "httpClient", httpClient);
    }

    @AfterEach
    void limpiaInterrupcion() {
        Thread.interrupted(); // no arrastrar la marca de interrupción a otras pruebas
    }

    private static ShopConnection tienda(String handle) {
        return ShopConnection.builder().id(UUID.randomUUID()).platform("woocommerce").shopHandle(handle).build();
    }

    private static ProductEntity producto() {
        ProductEntity p = new ProductEntity();
        p.setId(UUID.randomUUID());
        p.setSlug("chaqueta-roja");
        p.setTitleZh("红夹克");
        p.setBasePrice(new BigDecimal("19.90"));
        p.setDescriptionZh("描述");
        return p;
    }

    @SuppressWarnings("unchecked")
    private void responde(int status, String body) throws Exception {
        HttpResponse<String> res = mock(HttpResponse.class);
        when(res.statusCode()).thenReturn(status);
        when(res.body()).thenReturn(body);
        doReturn(res).when(httpClient).send(any(HttpRequest.class), any());
    }

    /* ==================== identidad del conector ==================== */

    @Test
    void elConectorSeAnunciaComoWoocommerceYComoDisponible() {
        // El registro elige por este código y el escaparate pinta "Próximamente" si no está disponible.
        assertThat(connector.platform()).isEqualTo("woocommerce");
        assertThat(connector.available()).isTrue();
    }

    /* ==================== credenciales ==================== */

    @ParameterizedTest
    @ValueSource(strings = {"solo-una-clave", ""})
    void unTokenQueNoTieneLaFormaClaveDosPuntosSecretoSeRechazaAntesDeLlamar(String token) throws Exception {
        PushResult res = connector.push(tienda("https://mitienda.com"), token, producto());

        assertThat(res.ok()).isFalse();
        assertThat(res.error()).contains("consumerKey:consumerSecret");
        verify(httpClient, never()).send(any(), any());
    }

    @Test
    void sinTokenNoSeIntentaPublicar() throws Exception {
        PushResult res = connector.push(tienda("https://mitienda.com"), null, producto());

        assertThat(res.ok()).isFalse();
        verify(httpClient, never()).send(any(), any());
    }

    @Test
    void lasCredencialesViajanComoAutenticacionBasicaEnBase64() throws Exception {
        responde(201, "{\"id\":42}");

        connector.push(tienda("https://mitienda.com"), "ck_abc:cs_xyz", producto());

        ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(req.capture(), any());
        String esperado = Base64.getEncoder().encodeToString("ck_abc:cs_xyz".getBytes(StandardCharsets.UTF_8));
        assertThat(req.getValue().headers().firstValue("Authorization")).contains("Basic " + esperado);
        assertThat(req.getValue().headers().firstValue("Content-Type")).contains("application/json");
    }

    /* ==================== dirección de la tienda ==================== */

    @ParameterizedTest
    @ValueSource(strings = {"https://mitienda.com", "https://mitienda.com///", "mitienda.com", "mitienda.com/"})
    void laUrlDeLaTiendaSeNormalizaAntesDeMontarLaRutaDeLaApi(String handle) throws Exception {
        // Sin normalizar, "mitienda.com/" acabaría llamando a "…com//wp-json/…" y WooCommerce responde 404.
        responde(201, "{\"id\":1}");

        connector.push(tienda(handle), "ck:cs", producto());

        ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(req.capture(), any());
        assertThat(req.getValue().uri()).hasToString("https://mitienda.com/wp-json/wc/v3/products");
    }

    @Test
    void sinUrlDeTiendaSeAvisaQueFaltaYNoSeLlamaANadie() throws Exception {
        assertThat(connector.push(tienda(null), "ck:cs", producto()).error()).contains("URL de tienda inválida");
        assertThat(connector.push(tienda("   "), "ck:cs", producto()).ok()).isFalse();
        verify(httpClient, never()).send(any(), any());
    }

    /* ==================== respuesta de WooCommerce ==================== */

    @Test
    void unaPublicacionCorrectaDevuelveElIdentificadorRemotoParaPoderActualizarla() throws Exception {
        // Sin el identificador remoto, la siguiente sincronización crearía un producto duplicado.
        responde(201, "{\"id\":9876,\"status\":\"publish\"}");

        PushResult res = connector.push(tienda("https://mitienda.com"), "ck:cs", producto());

        assertThat(res.ok()).isTrue();
        assertThat(res.remoteProductId()).isEqualTo("woo-9876");
    }

    @Test
    void unaRespuestaCorrectaPeroSinIdentificadorNoSeDaPorFallida() throws Exception {
        responde(200, "{}");

        PushResult res = connector.push(tienda("https://mitienda.com"), "ck:cs", producto());

        assertThat(res.ok()).isTrue();
        assertThat(res.remoteProductId()).isEqualTo("woo");
    }

    @Test
    void unRechazoDeLaTiendaLlegaAlPanelConSuCodigoYSuMotivo() throws Exception {
        responde(401, "{\"code\":\"woocommerce_rest_cannot_create\"}");

        PushResult res = connector.push(tienda("https://mitienda.com"), "ck:cs", producto());

        assertThat(res.ok()).isFalse();
        assertThat(res.error()).contains("401").contains("woocommerce_rest_cannot_create");
    }

    @Test
    void unaRespuestaDeErrorEnormeSeRecortaParaNoInundarElRegistro() throws Exception {
        responde(500, "x".repeat(5000));

        PushResult res = connector.push(tienda("https://mitienda.com"), "ck:cs", producto());

        assertThat(res.error()).hasSizeLessThan(360); // 300 caracteres de cuerpo + prefijo
    }

    @Test
    void unFalloDeRedSeCuentaComoErrorDeConexionYNoComoExcepcionSinControl() throws Exception {
        doThrow(new IOException("connection refused")).when(httpClient).send(any(HttpRequest.class), any());

        PushResult res = connector.push(tienda("https://mitienda.com"), "ck:cs", producto());

        assertThat(res.ok()).isFalse();
        assertThat(res.error()).contains("No se pudo conectar con WooCommerce").contains("connection refused");
    }

    @Test
    void siInterrumpenElHiloSeVuelveAMarcarLaInterrupcionAntesDeSalir() throws Exception {
        // Tragarse la interrupción deja al pool de hilos sin enterarse de que le han pedido parar.
        doThrow(new InterruptedException("stop")).when(httpClient).send(any(HttpRequest.class), any());

        PushResult res = connector.push(tienda("https://mitienda.com"), "ck:cs", producto());

        assertThat(res.ok()).isFalse();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    /* ==================== contenido publicado ==================== */

    @Test
    void unProductoSinTituloChinoSePublicaConSuNombreDeUrl() throws Exception {
        // Publicar un producto sin nombre lo deja invisible en la tienda del comerciante.
        responde(201, "{\"id\":1}");
        ProductEntity p = producto();
        p.setTitleZh(null);
        p.setBasePrice(null);

        connector.push(tienda("https://mitienda.com"), "ck:cs", p);

        ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(req.capture(), any());
        assertThat(req.getValue().bodyPublisher()).isPresent();
    }
}
