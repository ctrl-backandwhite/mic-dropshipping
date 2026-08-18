package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.cj;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Del SKU nuestro al identificador de variante que CJ exige para emitir la guía.
 *
 * <p>El dueño deposita la mercancía en el almacén de CJ <b>a mano</b> y la da de alta con el
 * <b>mismo SKU</b> que ya tienen nuestras variantes. Ese es todo el puente entre los dos catálogos: el
 * nuestro viene de 1688 y no sabe nada del {@code vid} de CJ, y {@code createOrderV3} no despacha sin él.
 *
 * <p>Las respuestas de aquí <b>no son capturas reales</b>: al 18-ago-2026 el inventario privado de CJ
 * está vacío, así que están reconstruidas a partir de la lista de campos que publica la documentación de
 * {@code querySkuDetailPage}. Por eso las pruebas fijan sobre todo el <b>comportamiento</b> —qué SKU se
 * elige, cuántas veces se llama, qué pasa cuando no está— y toleran a propósito varias formas del
 * envoltorio: lo que no puede cambiar cuando se contraste contra la API real es la conducta.
 */
class CjInventoryLookupTest {

    private static final String SKU = "NX-CAM-AZ-M";

    /** Respuesta con la variante buscada, en la forma que describe la documentación. */
    private static final String RESPUESTA_CON_LA_VARIANTE = """
            {"code":200,"result":true,"message":"Success","data":{
              "pageSize":20,"pageNumber":1,"totalRecords":1,"totalPages":1,
              "content":[
                {"merchantId":"33689","sku":"NX-CAM-AZ-M","variantId":"1564849338719199233",
                 "productId":"2408231029371914200","productName":"Camiseta de algodón",
                 "storageId":"1","variantKey":"Azul-M","clientAvailableQuantity":12}
              ]}}
            """;

    /** El SKU está en CJ pero acompañado de sus hermanas de talla, que empiezan igual. */
    private static final String RESPUESTA_CON_VARIAS_VARIANTES = """
            {"code":200,"result":true,"message":"Success","data":{
              "pageSize":20,"pageNumber":1,"totalRecords":3,"totalPages":1,
              "content":[
                {"sku":"NX-CAM-AZ-L","variantId":"1111111111111111111","variantKey":"Azul-L"},
                {"sku":"NX-CAM-AZ-M","variantId":"2222222222222222222","variantKey":"Azul-M"},
                {"sku":"NX-CAM-AZ-S","variantId":"3333333333333333333","variantKey":"Azul-S"}
              ]}}
            """;

    /** CJ contesta que todo fue bien y no trae nada: es lo que devuelve un SKU que no está depositado. */
    private static final String RESPUESTA_SIN_RESULTADOS = """
            {"code":200,"result":true,"message":"Success","data":{
              "pageSize":20,"pageNumber":1,"totalRecords":0,"totalPages":0,"content":[]}}
            """;

    // ------------------------------------------------------------------ lo que se lee

    @Test
    @DisplayName("del SKU depositado en CJ se saca su identificador de variante")
    void leeElVariantIdDelSku() {
        Optional<String> variantId = CjInventoryLookup.leerVariantId(RESPUESTA_CON_LA_VARIANTE, SKU);

        assertThat(variantId).contains("1564849338719199233");
    }

    @Test
    @DisplayName("con varias variantes se elige la del SKU exacto, nunca la primera de la lista")
    void eligeLaDelSkuExacto() {
        Optional<String> variantId = CjInventoryLookup.leerVariantId(RESPUESTA_CON_VARIAS_VARIANTES, SKU);

        // Quedarse con la primera despacharía una talla L a quien compró una M: el error no lo ve nadie
        // hasta que el cliente abre el paquete, y para entonces la guía ya está emitida y pagada.
        assertThat(variantId).contains("2222222222222222222");
    }

    @Test
    @DisplayName("un SKU que solo se parece no vale: NX-CAM-AZ-M no es NX-CAM-AZ-M2")
    void noValeUnSkuParecido() {
        String respuesta = """
                {"code":200,"result":true,"data":{"content":[
                  {"sku":"NX-CAM-AZ-M2","variantId":"9999999999999999999"}]}}
                """;

        assertThat(CjInventoryLookup.leerVariantId(respuesta, SKU)).isEmpty();
    }

    @Test
    @DisplayName("el SKU se compara sin los espacios que trae CJ, que no son parte del código")
    void ignoraLosEspaciosAlrededor() {
        String respuesta = """
                {"code":200,"result":true,"data":{"content":[
                  {"sku":" NX-CAM-AZ-M ","variantId":"1564849338719199233"}]}}
                """;

        assertThat(CjInventoryLookup.leerVariantId(respuesta, "  NX-CAM-AZ-M  "))
                .contains("1564849338719199233");
    }

    @Test
    @DisplayName("un variantId que viaja como número se lee como texto, igual que el openId")
    void aceptaElVariantIdNumerico() {
        // El openId de la autenticación ya llegó como número donde la documentación decía cadena; el
        // vid tiene diecinueve cifras y cabe en un long, así que puede pasar exactamente lo mismo.
        String respuesta = """
                {"code":200,"result":true,"data":{"content":[
                  {"sku":"NX-CAM-AZ-M","variantId":1564849338719199233}]}}
                """;

        assertThat(CjInventoryLookup.leerVariantId(respuesta, SKU)).contains("1564849338719199233");
    }

    @Test
    @DisplayName("si CJ llama vid al campo se lee igual, porque así lo pide createOrderV3")
    void aceptaElNombreVid() {
        // La documentación de inventario dice variantId y la de creación del pedido pide vid. No están
        // contrastados contra la API real, así que se admiten los dos nombres en vez de apostar por uno.
        String respuesta = """
                {"code":200,"result":true,"data":{"content":[
                  {"sku":"NX-CAM-AZ-M","vid":"1564849338719199233"}]}}
                """;

        assertThat(CjInventoryLookup.leerVariantId(respuesta, SKU)).contains("1564849338719199233");
    }

    @Test
    @DisplayName("da igual cómo se llame la lista dentro de data: content, list o la propia data")
    void toleraLasFormasDelEnvoltorio() {
        String comoLista = """
                {"code":200,"result":true,"data":{"list":[
                  {"sku":"NX-CAM-AZ-M","variantId":"1564849338719199233"}]}}
                """;
        String comoArrayDirecto = """
                {"code":200,"result":true,"data":[
                  {"sku":"NX-CAM-AZ-M","variantId":"1564849338719199233"}]}
                """;

        assertThat(CjInventoryLookup.leerVariantId(comoLista, SKU)).contains("1564849338719199233");
        assertThat(CjInventoryLookup.leerVariantId(comoArrayDirecto, SKU)).contains("1564849338719199233");
    }

    @Test
    @DisplayName("si el SKU no está depositado no se inventa un identificador")
    void skuAusenteNoDevuelveNada() {
        assertThat(CjInventoryLookup.leerVariantId(RESPUESTA_SIN_RESULTADOS, SKU)).isEmpty();
    }

    @Test
    @DisplayName("un error de CJ no se confunde con un SKU ausente ni revienta el despacho")
    void errorDeCj() {
        String error = """
                {"code":1600200,"result":false,"message":"token is invalid","data":null}
                """;

        assertThatCode(() -> assertThat(CjInventoryLookup.leerVariantId(error, SKU)).isEmpty())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("una respuesta ilegible se descarta sin lanzar")
    void respuestaIlegible() {
        assertThatCode(() -> assertThat(CjInventoryLookup.leerVariantId("<html>502</html>", SKU)).isEmpty())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("la consulta se hace por el SKU, y unas comillas en el código no rompen el JSON")
    void elCuerpoLlevaElSku() {
        assertThat(CjInventoryLookup.cuerpoDeConsulta(SKU)).contains("\"sku\":\"NX-CAM-AZ-M\"");
        assertThat(CjInventoryLookup.cuerpoDeConsulta("NX\"RARO")).doesNotContain("NX\"RARO");
    }

    // ------------------------------------------------------------------ la caché y los fallos

    @Test
    @DisplayName("dos despachos del mismo SKU cuestan una sola petición a CJ")
    void cacheaElResultado() {
        InventarioFalso inventario = new InventarioFalso();
        inventario.responde(SKU, RESPUESTA_CON_LA_VARIANTE);
        CjInventoryLookup buscador = new CjInventoryLookup(inventario);

        assertThat(buscador.variantIdDe(SKU)).contains("1564849338719199233");
        assertThat(buscador.variantIdDe(SKU)).contains("1564849338719199233");

        // CJ admite una petición por segundo: un pedido de seis líneas del mismo artículo no puede
        // gastar seis llamadas, y el vid de un SKU no cambia nunca.
        assertThat(inventario.consultados).containsExactly(SKU);
    }

    @Test
    @DisplayName("cada SKU distinto se consulta una vez")
    void cadaSkuSeConsultaUnaVez() {
        InventarioFalso inventario = new InventarioFalso();
        inventario.responde(SKU, RESPUESTA_CON_LA_VARIANTE);
        inventario.responde("NX-CAM-AZ-L", RESPUESTA_CON_VARIAS_VARIANTES.replace("NX-CAM-AZ-M", "NX-X"));
        CjInventoryLookup buscador = new CjInventoryLookup(inventario);

        buscador.variantIdDe(SKU);
        buscador.variantIdDe("NX-CAM-AZ-L");
        buscador.variantIdDe(SKU);

        assertThat(inventario.consultados).containsExactly(SKU, "NX-CAM-AZ-L");
    }

    @Test
    @DisplayName("el aviso de SKU no encontrado dice qué SKU se buscó")
    void elAvisoDiceElSku() {
        InventarioFalso inventario = new InventarioFalso();
        CjInventoryLookup buscador = new CjInventoryLookup(inventario);

        assertThat(buscador.variantIdDe(SKU)).isEmpty();
        // La causa más probable es una errata al teclear el SKU depositando el lote en CJ. Sin el código
        // escrito en el aviso, quien mire la bandeja de incidencias no tiene por dónde empezar.
        assertThat(CjInventoryLookup.mensajeDeSkuNoEncontrado(SKU)).contains(SKU);
    }

    @Test
    @DisplayName("un SKU que no está no se cachea: al corregir la errata en CJ vuelve a preguntarse")
    void noCacheaLoQueNoEncontro() {
        InventarioFalso inventario = new InventarioFalso();
        CjInventoryLookup buscador = new CjInventoryLookup(inventario);

        assertThat(buscador.variantIdDe(SKU)).isEmpty();
        inventario.responde(SKU, RESPUESTA_CON_LA_VARIANTE);

        assertThat(buscador.variantIdDe(SKU)).contains("1564849338719199233");
    }

    @Test
    @DisplayName("con CJ caído se devuelve vacío, no una excepción que tumbe el despacho")
    void cjCaido() {
        CjInventoryLookup buscador = new CjInventoryLookup(new InventarioCaido());

        assertThat(buscador.variantIdDe(SKU)).isEmpty();
    }

    @Test
    @DisplayName("sin SKU no se molesta a CJ")
    void skuVacio() {
        InventarioFalso inventario = new InventarioFalso();
        CjInventoryLookup buscador = new CjInventoryLookup(inventario);

        assertThat(buscador.variantIdDe(null)).isEmpty();
        assertThat(buscador.variantIdDe("   ")).isEmpty();
        assertThat(inventario.consultados).isEmpty();
    }

    // ------------------------------------------------------------------ dobles

    /** Inventario de CJ de mentira que apunta cada consulta para poder contarlas. */
    private static final class InventarioFalso implements CjInventoryLookup.ConsultaDeInventario {

        private final Map<String, String> respuestas = new HashMap<>();
        private final List<String> consultados = new ArrayList<>();

        private void responde(String sku, String respuesta) {
            respuestas.put(sku, respuesta);
        }

        @Override
        public String respuestaParaSku(String sku) {
            consultados.add(sku);
            return respuestas.getOrDefault(sku, RESPUESTA_SIN_RESULTADOS);
        }
    }

    /** CJ sin servicio: la llamada revienta, como reviente la red o caduque el tiempo de espera. */
    private static final class InventarioCaido implements CjInventoryLookup.ConsultaDeInventario {

        @Override
        public String respuestaParaSku(String sku) {
            throw new IllegalStateException("No se pudo contactar con el inventario de CJ.");
        }
    }
}
