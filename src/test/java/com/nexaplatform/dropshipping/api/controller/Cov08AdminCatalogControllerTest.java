package com.nexaplatform.dropshipping.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.dto.in.AddProductImageDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.BulkResultDtoOut;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.application.usecase.CatalogUseCase;
import com.nexaplatform.dropshipping.application.usecase.CatalogUseCase.BulkOutcome;
import com.nexaplatform.dropshipping.application.usecase.CatalogUseCase.ProductExportBatch;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exportación e importación masiva del catálogo por NDJSON, y forma de las respuestas en lote.
 *
 * <p>Es el camino que mueve catálogos de miles de productos. Lo que se fija aquí: que la exportación
 * vaya volcando página a página (no puede cargar el catálogo entero en memoria), que termine cuando el
 * caso de uso dice que no queda nada —si no, se quedaría en bucle infinito—, que acote con los MISMOS
 * filtros que la lista del panel, y que una línea corrupta a mitad de un fichero de miles NO aborte la
 * importación entera sino que se cuente como fallida.
 */
class Cov08AdminCatalogControllerTest {

    private CatalogUseCase catalogUseCase;
    private ObjectMapper objectMapper;
    private AdminCatalogController controller;

    @BeforeEach
    void setUp() {
        catalogUseCase = mock(CatalogUseCase.class);
        objectMapper = new ObjectMapper();
        // El espejado entra en el constructor desde que el panel puede devolver imágenes a la cola para
        // que pasen por el compresor. Aquí no se ejerce, pero el controlador lo necesita para construirse.
        controller = new AdminCatalogController(catalogUseCase, objectMapper,
                mock(com.nexaplatform.dropshipping.infrastructure.integration.storage.ImageMirrorService.class));
    }

    private static BulkProductDtoIn producto(String titulo) {
        BulkProductDtoIn dto = new BulkProductDtoIn();
        dto.setTitleEs(titulo);
        dto.setCategorySlug("moda-mujer");
        return dto;
    }

    private String exportar(int batch) throws IOException {
        return exportar(batch, null);
    }

    /** La misma exportación acotada por certificación: null = todos, true = solo los certificados. */
    private String exportar(int batch, Boolean verified) throws IOException {
        ResponseEntity<StreamingResponseBody> response = controller.exportProductsNdjson(batch, null, null, verified,
                null, null, null, null, null, null, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Una página del volcado con las filas dadas, diciendo si detrás queda alguna más. */
    private static ProductExportBatch lote(boolean hayMas, BulkProductDtoIn... filas) {
        return new ProductExportBatch(List.of(filas), hayMas);
    }

    private static HttpServletRequest cuerpo(String ndjson) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/api/admin/catalog/products/import/ndjson");
        request.setContent(ndjson.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    // ─────────────────────── exportación NDJSON ───────────────────────

    @Test
    void laExportacionEmiteUnProductoPorLinea() throws IOException {
        when(catalogUseCase.exportPage(eq(0), eq(200), any()))
                .thenReturn(lote(false, producto("Camiseta"), producto("Gorra")));

        String ndjson = exportar(200);

        assertThat(ndjson.lines()).hasSize(2);
        assertThat(ndjson).contains("\"titleEs\":\"Camiseta\"").contains("\"titleEs\":\"Gorra\"");
    }

    @Test
    void laExportacionPasaElFiltroDeCertificacionAlUseCase() throws IOException {
        // Si el filtro se quedara en el controlador, el panel diría «solo certificados» y bajaría todo.
        when(catalogUseCase.exportPage(anyInt(), anyInt(), any())).thenReturn(lote(false, producto("Camiseta")));

        String ndjson = exportar(200, true);

        assertThat(ndjson.lines()).hasSize(1);
        assertThat(filtroVolcado().verified()).isTrue();
    }

    @Test
    void sinFiltroDeCertificacionSePideElCatalogoEntero() throws IOException {
        // «Todos» tiene que viajar como nulo y no como false: false son solo los NO certificados.
        when(catalogUseCase.exportPage(anyInt(), anyInt(), any())).thenReturn(lote(false, producto("Camiseta")));

        exportar(200);

        assertThat(filtroVolcado().verified()).isNull();
    }

    /**
     * La exportación acota con LOS MISMOS filtros que la lista del panel.
     *
     * <p>Antes solo conocía fecha y certificación: filtrar la lista a treinta productos y abrir «Exportar»
     * ofrecía los nueve mil del catálogo, y lo que se descargaba no era lo que se estaba mirando.
     */
    @Test
    void laExportacionAcotaConLosMismosFiltrosQueLaLista() throws IOException {
        when(catalogUseCase.exportPage(anyInt(), anyInt(), any())).thenReturn(lote(false));
        UUID categoria = UUID.randomUUID();

        controller.exportProductsNdjson(200, null, null, null, "ACTIVE", categoria, "bailarinas", BigDecimal.ONE,
                BigDecimal.TEN, 50, BigDecimal.valueOf(0.4)).getBody().writeTo(new ByteArrayOutputStream());

        CatalogUseCase.ExportFilter filtro = filtroVolcado();
        assertThat(filtro.status()).isEqualTo("ACTIVE");
        assertThat(filtro.categoryId()).isEqualTo(categoria);
        assertThat(filtro.q()).isEqualTo("bailarinas");
        assertThat(filtro.minCost()).isEqualTo(BigDecimal.ONE);
        assertThat(filtro.maxCost()).isEqualTo(BigDecimal.TEN);
        assertThat(filtro.minSales()).isEqualTo(50);
        assertThat(filtro.minTrend()).isEqualTo(BigDecimal.valueOf(0.4));
    }

    /** El rango de fechas del panel llega como día ISO y sale como instante; el superior, EXCLUSIVO. */
    @Test
    void elRangoDeFechasSeTraduceAInstantesConElLimiteSuperiorExclusivo() throws IOException {
        when(catalogUseCase.exportPage(anyInt(), anyInt(), any())).thenReturn(lote(false));

        controller.exportProductsNdjson(200, "2026-08-17", "2026-09-17", null, null, null, null, null, null, null, null)
                .getBody().writeTo(new ByteArrayOutputStream());

        CatalogUseCase.ExportFilter filtro = filtroVolcado();
        assertThat(filtro.createdFrom()).isEqualTo(Instant.parse("2026-08-17T00:00:00Z"));
        assertThat(filtro.createdTo()).isEqualTo(Instant.parse("2026-09-18T00:00:00Z"));
    }

    @Test
    void laExportacionSigueEncadenandoPaginasHastaQueNoQuedaNinguna() throws IOException {
        // Es lo que permite exportar catálogos enteros con memoria acotada: se vuelca página a página y se
        // sigue mientras el caso de uso diga que detrás queda algo.
        when(catalogUseCase.exportPage(eq(0), eq(2), any())).thenReturn(lote(true, producto("A"), producto("B")));
        when(catalogUseCase.exportPage(eq(1), eq(2), any())).thenReturn(lote(false, producto("C")));

        assertThat(exportar(2).lines()).hasSize(3);

        verify(catalogUseCase).exportPage(eq(0), eq(2), any());
        verify(catalogUseCase).exportPage(eq(1), eq(2), any());
    }

    @Test
    void laExportacionTerminaCuandoElCasoDeUsoDiceQueNoQuedaNada() throws IOException {
        // Sin esa condición el bucle pediría páginas vacías para siempre.
        when(catalogUseCase.exportPage(anyInt(), eq(5), any())).thenReturn(lote(false, producto("Único")));

        exportar(5);

        verify(catalogUseCase, times(1)).exportPage(anyInt(), anyInt(), any());
    }

    @Test
    void unCatalogoVacioProduceUnaExportacionVacia() throws IOException {
        when(catalogUseCase.exportPage(anyInt(), anyInt(), any())).thenReturn(lote(false));

        assertThat(exportar(200)).isEmpty();
    }

    @Test
    void elTamanoDeLoteSeAcotaAUnRangoRazonable() throws IOException {
        // Un 0 dejaría el bucle sin avanzar y un valor enorme se comería la memoria que precisamente se
        // quiere acotar.
        when(catalogUseCase.exportPage(anyInt(), anyInt(), any())).thenReturn(lote(false));

        exportar(0);
        exportar(999_999);

        ArgumentCaptor<Integer> tamanos = ArgumentCaptor.forClass(Integer.class);
        verify(catalogUseCase, times(2)).exportPage(anyInt(), tamanos.capture(), any());
        assertThat(tamanos.getAllValues()).containsExactly(1, 1000);
    }

    @Test
    void laExportacionSeSirveComoDescargaNdjson() {
        ResponseEntity<StreamingResponseBody> response = controller.exportProductsNdjson(200, null, null, null, null,
                null, null, null, null, null, null);

        assertThat(response.getHeaders().getContentType()).hasToString("application/x-ndjson");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment")
                .contains("products-export.ndjson");
    }

    /** El filtro con el que el controlador acabó pidiendo el volcado. */
    private CatalogUseCase.ExportFilter filtroVolcado() {
        ArgumentCaptor<CatalogUseCase.ExportFilter> captor = ArgumentCaptor.forClass(CatalogUseCase.ExportFilter.class);
        verify(catalogUseCase, atLeastOnce()).exportPage(anyInt(), anyInt(), captor.capture());
        return captor.getValue();
    }

    // ─────────────────────── importación NDJSON ───────────────────────

    @Test
    void laImportacionMandaLasFilasEnLotesDelTamanoPedido() {
        when(catalogUseCase.bulkCreateProducts(anyList())).thenReturn(new BulkResultDtoOut(2, 0, List.of()));
        String ndjson = "{\"titleEs\":\"A\"}\n{\"titleEs\":\"B\"}\n{\"titleEs\":\"C\"}\n";

        BulkResultDtoOut resultado = controller.importProductsNdjson(cuerpo(ndjson), 2).getBody();

        // Dos llamadas: el lote completo de 2 y el resto de 1. Si el resto no se vaciara al final, la
        // última fila del fichero se perdería en silencio.
        verify(catalogUseCase, times(2)).bulkCreateProducts(anyList());
        assertThat(resultado.getCreated()).isEqualTo(4);
    }

    @Test
    void unaLineaCorruptaNoAbortaLaImportacionEntera() {
        // Un fichero de miles de líneas no puede perderse por una sola mal formada: se cuenta como
        // fallida, se anota el motivo y se sigue.
        when(catalogUseCase.bulkCreateProducts(anyList())).thenReturn(new BulkResultDtoOut(2, 0, List.of()));
        String ndjson = "{\"titleEs\":\"A\"}\nesto no es json\n{\"titleEs\":\"B\"}\n";

        BulkResultDtoOut resultado = controller.importProductsNdjson(cuerpo(ndjson), 200).getBody();

        assertThat(resultado.getCreated()).isEqualTo(2);
        assertThat(resultado.getFailed()).isEqualTo(1);
        assertThat(resultado.getErrors()).hasSize(1);
        assertThat(resultado.getErrors().get(0)).startsWith("parse:");
    }

    @Test
    void lasLineasEnBlancoSeIgnoranSinContarComoError() {
        when(catalogUseCase.bulkCreateProducts(anyList())).thenReturn(new BulkResultDtoOut(1, 0, List.of()));
        String ndjson = "\n   \n{\"titleEs\":\"A\"}\n\n";

        BulkResultDtoOut resultado = controller.importProductsNdjson(cuerpo(ndjson), 200).getBody();

        assertThat(resultado.getFailed()).isZero();
        verify(catalogUseCase, times(1)).bulkCreateProducts(anyList());
    }

    @Test
    void unaImportacionSinFilasNoLlamaAlCasoDeUso() {
        BulkResultDtoOut resultado = controller.importProductsNdjson(cuerpo(""), 200).getBody();

        assertThat(resultado.getCreated()).isZero();
        assertThat(resultado.getFailed()).isZero();
        verify(catalogUseCase, never()).bulkCreateProducts(anyList());
    }

    @Test
    void laListaDeErroresSeAcotaAunConMilesDeLineasMalas() {
        // Devolver un error por cada línea de un fichero corrupto de 100.000 filas tumbaría la respuesta.
        StringBuilder ndjson = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            ndjson.append("linea corrupta ").append(i).append('\n');
        }

        BulkResultDtoOut resultado = controller.importProductsNdjson(cuerpo(ndjson.toString()), 200).getBody();

        assertThat(resultado.getFailed()).isEqualTo(250);
        assertThat(resultado.getErrors()).hasSize(100);
    }

    @Test
    void losErroresDeCadaLoteSeAcumulanEnElResultadoFinal() {
        when(catalogUseCase.bulkCreateProducts(anyList()))
                .thenReturn(new BulkResultDtoOut(1, 1, List.of("fila 2: categoría desconocida")));
        String ndjson = "{\"titleEs\":\"A\"}\n{\"titleEs\":\"B\"}\n";

        BulkResultDtoOut resultado = controller.importProductsNdjson(cuerpo(ndjson), 1).getBody();

        assertThat(resultado.getCreated()).isEqualTo(2);
        assertThat(resultado.getFailed()).isEqualTo(2);
        assertThat(resultado.getErrors()).hasSize(2);
    }

    @Test
    void unLoteSinDetalleDeErroresNoRompeElAcumulado() {
        when(catalogUseCase.bulkCreateProducts(anyList())).thenReturn(new BulkResultDtoOut(1, 0, null));

        BulkResultDtoOut resultado = controller.importProductsNdjson(cuerpo("{\"titleEs\":\"A\"}\n"), 200).getBody();

        assertThat(resultado.getCreated()).isEqualTo(1);
        assertThat(resultado.getErrors()).isEmpty();
    }

    @Test
    void siElCuerpoNoSePuedeLeerSeAvisaEnLugarDeDejarUnaImportacionAMedias() throws IOException {
        HttpServletRequest roto = mock(HttpServletRequest.class);
        when(roto.getInputStream()).thenThrow(new IOException("conexión cortada"));

        assertThatThrownBy(() -> controller.importProductsNdjson(roto, 200)).isInstanceOf(BusinessException.class);
    }

    // ─────────────────────── respuestas en lote ───────────────────────

    @Test
    void elBorradoEnLoteInformaDeLosBorradosYDeCadaFalloPorSeparado() {
        // Los lotes no se paran ante el primer error: un producto con pedidos no puede impedir borrar
        // los demás de la selección, pero el motivo tiene que llegar al panel.
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(catalogUseCase.bulkDeleteProducts(ids))
                .thenReturn(new BulkOutcome(2, List.of("El producto X tiene pedidos")));

        Map<String, Object> cuerpo = controller.bulkDeleteProducts(ids).getBody();

        assertThat(cuerpo).containsEntry("deleted", 2).containsEntry("failed", 1);
        assertThat((List<?>) cuerpo.get("errors")).hasSize(1);
    }

    @Test
    void elCambioDeEstadoEnLoteDevuelveElRecuentoYLosMotivos() {
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(catalogUseCase.bulkUpdateStatus(ids, "PAUSED")).thenReturn(new BulkOutcome(1, List.of("no existe")));

        Map<String, Object> cuerpo = controller
                .bulkProductStatus(new AdminCatalogController.BulkStatusRequest(ids, "PAUSED")).getBody();

        assertThat(cuerpo).containsEntry("succeeded", 1).containsEntry("failed", 1);
    }

    @Test
    void elRecuentoDeExportacionSeDevuelveBajoLaClaveCount() {
        when(catalogUseCase.countProducts(any())).thenReturn(1363L);

        assertThat(controller.exportCount(null, null, null, null, null, null, null, null, null, null).getBody())
                .containsEntry("count", 1363L);
    }

    // ─────────────────────── códigos de respuesta ───────────────────────

    @Test
    void lasOperacionesSinCuerpoRespondenSinContenido() {
        UUID id = UUID.randomUUID();

        assertThat(controller.deleteProduct(id).getStatusCode().value()).isEqualTo(204);
        assertThat(controller.deleteVariant(id).getStatusCode().value()).isEqualTo(204);
        assertThat(controller.deleteProductImage(id).getStatusCode().value()).isEqualTo(204);
        assertThat(controller.deleteProductVideo(id).getStatusCode().value()).isEqualTo(204);
        assertThat(controller.deleteVariantValue(id).getStatusCode().value()).isEqualTo(204);
        assertThat(controller.deletePriceTier(id, 10).getStatusCode().value()).isEqualTo(204);
        assertThat(controller.deleteCategory1688Mapping(id).getStatusCode().value()).isEqualTo(204);
        assertThat(controller.deleteCategoryAttributeSchema(id).getStatusCode().value()).isEqualTo(204);
        verify(catalogUseCase).deletePriceTier(id, 10);
    }

    @Test
    void anadirUnaImagenDevuelveCreado() {
        // 201 y no 200: el panel encadena la subida con la recarga de la galería según el código.
        UUID productId = UUID.randomUUID();

        assertThat(controller.addProductImage(productId, new AddProductImageDtoIn("http://cdn/a.jpg", "GALLERY"))
                .getStatusCode().value()).isEqualTo(201);
        verify(catalogUseCase).addProductImage(productId, "http://cdn/a.jpg", "GALLERY");
    }

    // ─────────────────────── edición puntual desde el panel ───────────────────────

    @Test
    void elMapeoDeCategoriasDe1688ConvierteElIdentificadorRecibido() {
        UUID categoryId = UUID.randomUUID();
        Map<String, String> body = Map.of("categoryId", categoryId.toString(), "external1688Id", "1688-77",
                "external1688Name", "女装");

        controller.upsertCategory1688Mapping(body);

        verify(catalogUseCase).upsertCategory1688Mapping("1688-77", "女装", categoryId);
    }

    @Test
    void elEsquemaDeAtributosTomaValoresPorDefectoCuandoElPanelNoLosManda() {
        // "required" ausente = no obligatorio, y sin posición el atributo va el primero: son los valores
        // que evitan que un alta incompleta deje el esquema en un estado imposible.
        UUID categoryId = UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("attrKey", "material");
        body.put("label", "Material");

        controller.upsertCategoryAttributeSchema(categoryId, body);

        verify(catalogUseCase).upsertCategoryAttributeSchema(categoryId, "material", "Material", false, 0);
    }

    @Test
    void elEsquemaDeAtributosAceptaLaPosicionComoNumero() {
        UUID categoryId = UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("attrKey", "material");
        body.put("label", "Material");
        body.put("required", Boolean.TRUE);
        body.put("position", 3);

        controller.upsertCategoryAttributeSchema(categoryId, body);

        verify(catalogUseCase).upsertCategoryAttributeSchema(categoryId, "material", "Material", true, 3);
    }

    // ─────────────────────── DROP-158: recargo fijo por producto (30-ago-2026) ───────────────────────

    /** Sin filtro (ni productIds ni categoryId) → update masivo para todo el catálogo. */
    @Test
    void elRecargoSinFiltroSeAplicaATodoElCatalogo() {
        com.nexaplatform.dropshipping.api.dto.in.AdminSurchargeBulkDtoIn req = new com.nexaplatform.dropshipping.api.dto.in.AdminSurchargeBulkDtoIn(
                new java.math.BigDecimal("2.00"), null, null);
        when(catalogUseCase.bulkUpdateSurcharge(null, null, new java.math.BigDecimal("2.00"))).thenReturn(1234);

        ResponseEntity<Map<String, Object>> r = controller.bulkUpdateSurcharge(req);

        assertThat(r.getBody()).isEqualTo(Map.of("updated", 1234));
        verify(catalogUseCase).bulkUpdateSurcharge(null, null, new java.math.BigDecimal("2.00"));
    }

    /** Con categoryId → update masivo solo de esa categoría. */
    @Test
    void elRecargoPorCategoriaSoloTocaLosProductosDeEsaCategoria() {
        UUID cat = UUID.randomUUID();
        com.nexaplatform.dropshipping.api.dto.in.AdminSurchargeBulkDtoIn req = new com.nexaplatform.dropshipping.api.dto.in.AdminSurchargeBulkDtoIn(
                new java.math.BigDecimal("5.00"), null, cat);
        when(catalogUseCase.bulkUpdateSurcharge(null, cat, new java.math.BigDecimal("5.00"))).thenReturn(42);

        controller.bulkUpdateSurcharge(req);

        verify(catalogUseCase).bulkUpdateSurcharge(null, cat, new java.math.BigDecimal("5.00"));
    }

    /** Con productIds → update solo de esos productos (manda sobre categoryId). */
    @Test
    void elRecargoPorProductoListaMandaSobreLaCategoria() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        com.nexaplatform.dropshipping.api.dto.in.AdminSurchargeBulkDtoIn req = new com.nexaplatform.dropshipping.api.dto.in.AdminSurchargeBulkDtoIn(
                new java.math.BigDecimal("3.00"), List.of(a, b), UUID.randomUUID());
        when(catalogUseCase.bulkUpdateSurcharge(List.of(a, b), req.getCategoryId(), new java.math.BigDecimal("3.00")))
                .thenReturn(2);

        ResponseEntity<Map<String, Object>> r = controller.bulkUpdateSurcharge(req);

        assertThat(r.getBody()).isEqualTo(Map.of("updated", 2));
        verify(catalogUseCase).bulkUpdateSurcharge(List.of(a, b), req.getCategoryId(),
                new java.math.BigDecimal("3.00"));
    }
}
