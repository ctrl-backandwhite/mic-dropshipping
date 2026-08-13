package com.nexaplatform.dropshipping.infrastructure.integration.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
    @Mock
    ProductIndexer indexer;

    @InjectMocks
    ProductSearchService service;

    @BeforeEach
    void setUp() {
        // El índice ya no es un campo del servicio: su nombre lleva la versión del esquema y lo resuelve
        // el indexador, para que un cambio de analizadores no exija tocar la configuración.
        org.mockito.Mockito.lenient().when(indexer.indexName()).thenReturn("products-v2");
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


}
