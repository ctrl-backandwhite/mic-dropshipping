package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.nexaplatform.dropshipping.api.dto.out.SearchResultDtoOut;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ShardStatistics;
import org.opensearch.client.opensearch._types.SortOptions;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.core.search.HitsMetadata;
import org.opensearch.client.opensearch.core.search.TotalHits;
import org.opensearch.client.opensearch.core.search.TotalHitsRelation;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Búsqueda de productos contra OpenSearch. Se fija el saneado de la paginación (un {@code size}
 * disparatado o negativo no puede llegar al motor ni convertirse en un 500), el filtro de imagen que
 * comparte criterio con el escaparate, y que un motor caído devuelva un resultado vacío en vez de
 * tumbar la página de búsqueda.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov05ProductSearchServiceTest {

    @Mock
    OpenSearchClient client;

    @InjectMocks
    ProductSearchService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "index", "products");
    }

    @SuppressWarnings("rawtypes")
    private SearchResponse<Map> response(Long total, List<Hit<Map>> hits) {
        HitsMetadata.Builder<Map> meta = new HitsMetadata.Builder<Map>().hits(hits);
        if (total != null) {
            meta.total(new TotalHits.Builder().value(total).relation(TotalHitsRelation.Eq).build());
        }
        return new SearchResponse.Builder<Map>().took(4).timedOut(false)
                .shards(new ShardStatistics.Builder().total(1).successful(1).failed(0).build()).hits(meta.build())
                .build();
    }

    @SuppressWarnings("rawtypes")
    private Hit<Map> hit(String id, double score, Map<String, Object> source) {
        return new Hit.Builder<Map>().index("products").id(id).score(score).source(source).build();
    }

    @SuppressWarnings("rawtypes")
    private void stubSearch(SearchResponse<Map> response) throws IOException {
        // doReturn en vez de when().thenReturn(): search() es genérico y con when() el compilador
        // resuelve TDocument a Object, con lo que la respuesta tipada no encajaría.
        doReturn(response).when(client).search(any(SearchRequest.class), any());
    }

    private SearchRequest capturedRequest() throws IOException {
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(client).search(captor.capture(), any());
        return captor.getValue();
    }

    /* ===================== saneado de la paginación ===================== */

    @ParameterizedTest
    @CsvSource({"-10,1", "0,1", "20,20", "100,100", "5000,100"})
    @DisplayName("el tamaño de página se acota por arriba y por abajo antes de llegar al motor")
    void elTamanoDePaginaSeAcota(int requested, int expected) throws IOException {
        stubSearch(response(0L, List.of()));

        service.searchTyped("camisa", "es", 0, requested);

        // Un size negativo llegaba tal cual a OpenSearch y su error salía como 500 al usuario.
        assertThat(capturedRequest().size()).isEqualTo(expected);
    }

    @Test
    @DisplayName("una página negativa se trata como la primera")
    void unaPaginaNegativaSeTrataComoLaPrimera() throws IOException {
        stubSearch(response(0L, List.of()));

        service.searchTyped("camisa", "es", -3, 20);

        assertThat(capturedRequest().from()).isZero();
    }

    @Test
    @DisplayName("el desplazamiento se calcula con el tamaño YA acotado")
    void elDesplazamientoUsaElTamanoAcotado() throws IOException {
        stubSearch(response(0L, List.of()));

        service.searchTyped("camisa", "es", 2, 500);

        // 2 × 100 (el tope), no 2 × 500: si no, se pediría un offset que el motor rechaza.
        assertThat(capturedRequest().from()).isEqualTo(200);
    }

    /* ===================== construcción de la consulta ===================== */

    @Test
    @DisplayName("sin palabra clave se recorre todo el catálogo")
    void sinPalabraClaveSeRecorreTodoElCatalogo() throws IOException {
        stubSearch(response(0L, List.of()));

        service.searchTyped("   ", "es", 0, 10);

        BoolQuery root = capturedRequest().query().bool();
        assertThat(root.must()).hasSize(1);
        assertThat(root.must().get(0).isMatchAll()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"es,titleEs", "pt,titlePt", "zh,titleZh", "nl,titleNl"})
    @DisplayName("el idioma pedido decide el campo de título que se consulta")
    void elIdiomaDecideElCampoDeTitulo(String language, String expectedField) throws IOException {
        stubSearch(response(0L, List.of()));

        service.searchTyped("camisa", language, 0, 10);

        List<String> fields = capturedRequest().query().bool().must().get(0).multiMatch().fields();
        // Siempre se acompaña de titleZh/titleEn para no perder los productos aún sin traducir.
        assertThat(fields).contains(expectedField, "titleZh", "titleEn");
    }

    @Test
    @DisplayName("sin idioma indicado se busca en español")
    void sinIdiomaIndicadoSeBuscaEnEspanol() throws IOException {
        stubSearch(response(0L, List.of()));

        service.searchTyped("camisa", null, 0, 10);

        assertThat(capturedRequest().query().bool().must().get(0).multiMatch().fields()).contains("titleEs");
    }

    @Test
    @DisplayName("la búsqueda solo devuelve productos con imagen, tolerando los documentos aún sin el campo")
    void laBusquedaSoloDevuelveProductosConImagen() throws IOException {
        stubSearch(response(0L, List.of()));

        service.searchTyped("camisa", "es", 0, 10);

        BoolQuery root = capturedRequest().query().bool();
        assertThat(root.filter()).hasSize(1);
        BoolQuery imageFilter = root.filter().get(0).bool();
        assertThat(imageFilter.minimumShouldMatch()).isEqualTo("1");
        assertThat(imageFilter.should()).hasSize(2);
        Query withImage = imageFilter.should().get(0);
        assertThat(withImage.term().field()).isEqualTo("hasImage");
        // La segunda rama deja pasar los documentos antiguos sin el campo: si no, la búsqueda se vaciaría
        // durante el reindexado.
        Query legacy = imageFilter.should().get(1);
        assertThat(legacy.bool().mustNot().get(0).exists().field()).isEqualTo("hasImage");
    }

    @Test
    @DisplayName("los resultados se ordenan por tendencia, de mayor a menor")
    void losResultadosSeOrdenanPorTendencia() throws IOException {
        stubSearch(response(0L, List.of()));

        service.searchTyped("camisa", "es", 0, 10);

        List<SortOptions> sort = capturedRequest().sort();
        assertThat(sort).hasSize(1);
        assertThat(sort.get(0).field().field()).isEqualTo("trendScore");
        assertThat(sort.get(0).field().order()).isEqualTo(SortOrder.Desc);
    }

    @Test
    @DisplayName("la búsqueda ataca al índice configurado")
    void laBusquedaAtacaAlIndiceConfigurado() throws IOException {
        stubSearch(response(0L, List.of()));

        service.searchTyped("camisa", "es", 0, 10);

        assertThat(capturedRequest().index()).containsExactly("products");
    }

    /* ===================== resultados ===================== */

    @Test
    @DisplayName("cada resultado aplana el documento indexado junto a su id y su puntuación")
    void cadaResultadoAplanaElDocumentoConIdYPuntuacion() throws IOException {
        stubSearch(response(7L, List.of(hit("p-1", 2.5, Map.of("titleEs", "Camisa blanca", "trendScore", 91)))));

        SearchResultDtoOut result = service.searchTyped("camisa", "es", 0, 10);

        assertThat(result.getItems()).hasSize(1);
        Map<String, Object> source = result.getItems().get(0).getSource();
        assertThat(source).containsEntry("titleEs", "Camisa blanca").containsEntry("_id", "p-1")
                .containsEntry("_score", 2.5);
        assertThat(result.getTotal()).isEqualTo(7L);
    }

    @Test
    @DisplayName("si el motor no informa del total, se usa el número de resultados devueltos")
    void sinTotalDelMotorSeUsaElNumeroDeResultados() throws IOException {
        stubSearch(response(null, List.of(hit("p-1", 1.0, Map.of("titleEs", "A")),
                hit("p-2", 1.0, Map.of("titleEs", "B")))));

        assertThat(service.searchTyped("camisa", "es", 0, 10).getTotal()).isEqualTo(2L);
    }

    @Test
    @DisplayName("la envoltura devuelve la página y el tamaño que pidió el cliente")
    void laEnvolturaDevuelveLaPaginaYTamanoPedidos() throws IOException {
        stubSearch(response(0L, List.of()));

        SearchResultDtoOut result = service.searchTyped("camisa", "es", 3, 25);

        assertThat(result.getPage()).isEqualTo(3);
        assertThat(result.getSize()).isEqualTo(25);
    }

    /* ===================== motor caído ===================== */

    @Test
    @DisplayName("con el motor caído la búsqueda tipada devuelve vacío, no un error")
    void conElMotorCaidoLaBusquedaTipadaDevuelveVacio() throws IOException {
        when(client.search(any(SearchRequest.class), any())).thenThrow(new IOException("connection refused"));

        SearchResultDtoOut result = service.searchTyped("camisa", "es", 1, 10);

        assertThat(result.getItems()).isEmpty();
        assertThat(result.getTotal()).isZero();
    }

    @Test
    @DisplayName("con el motor caído la búsqueda en mapa conserva el contrato de la respuesta")
    void conElMotorCaidoLaBusquedaEnMapaConservaElContrato() throws IOException {
        when(client.search(any(SearchRequest.class), any())).thenThrow(new IOException("connection refused"));

        Map<String, Object> result = service.search("camisa", "es", 1, 10);

        assertThat(result).containsEntry("items", List.of()).containsEntry("total", 0L).containsEntry("page", 1)
                .containsEntry("size", 10);
    }

    @Test
    @DisplayName("la búsqueda en mapa devuelve el mismo contrato de claves que la tipada")
    void laBusquedaEnMapaDevuelveElMismoContrato() throws IOException {
        stubSearch(response(1L, List.of(hit("p-1", 3.0, Map.of("titleEs", "Camisa")))));

        Map<String, Object> result = service.search("camisa", "es", 0, 10);

        assertThat(result).containsKeys("items", "total", "page", "size");
        assertThat(result).containsEntry("total", 1L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items.get(0)).containsEntry("_id", "p-1").containsEntry("titleEs", "Camisa");
    }
}
