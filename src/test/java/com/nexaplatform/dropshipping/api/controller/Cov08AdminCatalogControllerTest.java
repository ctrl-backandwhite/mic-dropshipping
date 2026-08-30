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
import java.nio.charset.StandardCharsets;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exportación e importación masiva del catálogo por NDJSON, y forma de las respuestas en lote.
 *
 * <p>Es el camino que mueve catálogos de miles de productos. Lo que se fija aquí: que la exportación
 * pagine por keyset y vaya volcando (no puede cargar el catálogo entero en memoria), que termine cuando
 * llega una página incompleta —si no, se quedaría en bucle infinito—, y que una línea corrupta a mitad
 * de un fichero de miles NO aborte la importación entera sino que se cuente como fallida.
 */
class Cov08AdminCatalogControllerTest {

    private CatalogUseCase catalogUseCase;
    private ObjectMapper objectMapper;
    private AdminCatalogController controller;

    @BeforeEach
    void setUp() {
        catalogUseCase = mock(CatalogUseCase.class);
        objectMapper = new ObjectMapper();
        controller = new AdminCatalogController(catalogUseCase, objectMapper);
    }

    private static BulkProductDtoIn producto(String titulo) {
        BulkProductDtoIn dto = new BulkProductDtoIn();
        dto.setTitleEs(titulo);
        dto.setCategorySlug("moda-mujer");
        return dto;
    }

    private String exportar(int batch) throws IOException {
        ResponseEntity<StreamingResponseBody> response = controller.exportProductsNdjson(batch, null, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static HttpServletRequest cuerpo(String ndjson) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/catalog/products/import/ndjson");
        request.setContent(ndjson.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    // ─────────────────────── exportación NDJSON ───────────────────────

    @Test
    void laExportacionEmiteUnProductoPorLinea() throws IOException {
        when(catalogUseCase.exportBatchAfter(null, 200, null, null))
                .thenReturn(new ProductExportBatch(List.of(producto("Camiseta"), producto("Gorra")), null));

        String ndjson = exportar(200);

        assertThat(ndjson.lines()).hasSize(2);
        assertThat(ndjson).contains("\"titleEs\":\"Camiseta\"").contains("\"titleEs\":\"Gorra\"");
    }

    @Test
    void laExportacionSigueEncadenandoPaginasPorElUltimoIdentificador() throws IOException {
        // Es lo que permite exportar millones de productos con memoria acotada: cada página pide "los
        // siguientes a este id" en lugar de un OFFSET que se va degradando.
        UUID ultimoDeLaPrimera = UUID.randomUUID();
        List<BulkProductDtoIn> pagina = List.of(producto("A"), producto("B"));
        when(catalogUseCase.exportBatchAfter(null, 2, null, null)).thenReturn(new ProductExportBatch(pagina, ultimoDeLaPrimera));
        when(catalogUseCase.exportBatchAfter(ultimoDeLaPrimera, 2, null, null))
                .thenReturn(new ProductExportBatch(List.of(producto("C")), null));

        assertThat(exportar(2).lines()).hasSize(3);

        verify(catalogUseCase).exportBatchAfter(null, 2, null, null);
        verify(catalogUseCase).exportBatchAfter(ultimoDeLaPrimera, 2, null, null);
    }

    @Test
    void laExportacionTerminaCuandoLlegaUnaPaginaIncompleta() throws IOException {
        // Una página con menos filas de las pedidas es el fin del catálogo. Sin esta condición el bucle
        // no pararía nunca.
        when(catalogUseCase.exportBatchAfter(any(), eq(5), any(), any()))
                .thenReturn(new ProductExportBatch(List.of(producto("Único")), null));

        exportar(5);

        verify(catalogUseCase, times(1)).exportBatchAfter(any(), anyInt(), any(), any());
    }

    @Test
    void unCatalogoVacioProduceUnaExportacionVacia() throws IOException {
        when(catalogUseCase.exportBatchAfter(null, 200, null, null)).thenReturn(new ProductExportBatch(List.of(), null));

        assertThat(exportar(200)).isEmpty();
    }

    @Test
    void elTamanoDeLoteSeAcotaAUnRangoRazonable() throws IOException {
        // Un 0 dejaría el bucle sin avanzar y un valor enorme se comería la memoria que precisamente se
        // quiere acotar.
        when(catalogUseCase.exportBatchAfter(any(), anyInt(), any(), any())).thenReturn(new ProductExportBatch(List.of(), null));

        exportar(0);
        exportar(999_999);

        ArgumentCaptor<Integer> tamanos = ArgumentCaptor.forClass(Integer.class);
        verify(catalogUseCase, times(2)).exportBatchAfter(any(), tamanos.capture(), any(), any());
        assertThat(tamanos.getAllValues()).containsExactly(1, 1000);
    }

    @Test
    void laExportacionSeSirveComoDescargaNdjson() {
        ResponseEntity<StreamingResponseBody> response = controller.exportProductsNdjson(200, null, null);

        assertThat(response.getHeaders().getContentType()).hasToString("application/x-ndjson");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment").contains("products-export.ndjson");
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
        when(catalogUseCase.countProducts(null, null)).thenReturn(1363L);

        assertThat(controller.exportCount(null, null).getBody()).containsEntry("count", 1363L);
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
        com.nexaplatform.dropshipping.api.dto.in.AdminSurchargeBulkDtoIn req =
                new com.nexaplatform.dropshipping.api.dto.in.AdminSurchargeBulkDtoIn(new java.math.BigDecimal("2.00"),
                        null, null);
        when(catalogUseCase.bulkUpdateSurcharge(null, null, new java.math.BigDecimal("2.00"))).thenReturn(1234);

        ResponseEntity<Map<String, Object>> r = controller.bulkUpdateSurcharge(req);

        assertThat(r.getBody()).isEqualTo(Map.of("updated", 1234));
        verify(catalogUseCase).bulkUpdateSurcharge(null, null, new java.math.BigDecimal("2.00"));
    }

    /** Con categoryId → update masivo solo de esa categoría. */
    @Test
    void elRecargoPorCategoriaSoloTocaLosProductosDeEsaCategoria() {
        UUID cat = UUID.randomUUID();
        com.nexaplatform.dropshipping.api.dto.in.AdminSurchargeBulkDtoIn req =
                new com.nexaplatform.dropshipping.api.dto.in.AdminSurchargeBulkDtoIn(new java.math.BigDecimal("5.00"),
                        null, cat);
        when(catalogUseCase.bulkUpdateSurcharge(null, cat, new java.math.BigDecimal("5.00"))).thenReturn(42);

        controller.bulkUpdateSurcharge(req);

        verify(catalogUseCase).bulkUpdateSurcharge(null, cat, new java.math.BigDecimal("5.00"));
    }

    /** Con productIds → update solo de esos productos (manda sobre categoryId). */
    @Test
    void elRecargoPorProductoListaMandaSobreLaCategoria() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        com.nexaplatform.dropshipping.api.dto.in.AdminSurchargeBulkDtoIn req =
                new com.nexaplatform.dropshipping.api.dto.in.AdminSurchargeBulkDtoIn(new java.math.BigDecimal("3.00"),
                        List.of(a, b), UUID.randomUUID());
        when(catalogUseCase.bulkUpdateSurcharge(List.of(a, b), req.getCategoryId(), new java.math.BigDecimal("3.00")))
                .thenReturn(2);

        ResponseEntity<Map<String, Object>> r = controller.bulkUpdateSurcharge(req);

        assertThat(r.getBody()).isEqualTo(Map.of("updated", 2));
        verify(catalogUseCase).bulkUpdateSurcharge(List.of(a, b), req.getCategoryId(), new java.math.BigDecimal("3.00"));
    }
}
