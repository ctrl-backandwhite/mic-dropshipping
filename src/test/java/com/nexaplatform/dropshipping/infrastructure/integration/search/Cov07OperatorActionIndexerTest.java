package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OperatorOrderActionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.IndexResponse;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.core.search.HitsMetadata;
import org.opensearch.client.opensearch.core.search.TotalHits;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.CreateIndexResponse;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;
import org.opensearch.client.transport.endpoints.BooleanResponse;
import org.opensearch.client.util.ObjectBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Réplica en OpenSearch del histórico de acciones de operador. Postgres es la fuente de verdad, así
 * que indexar es "best effort" (nunca puede tumbar el flujo de negocio); la consulta, en cambio,
 * propaga el fallo para que el llamante decida el plan B.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov07OperatorActionIndexerTest {

    private static final String INDEX = "operator-actions";

    @Mock
    OpenSearchClient client;
    @Mock
    OpenSearchIndicesClient indices;

    @InjectMocks
    OperatorActionIndexer indexer;

    @BeforeEach
    void setUp() {
        // El nombre del índice llega por @Value, que Mockito no inyecta.
        ReflectionTestUtils.setField(indexer, "index", INDEX);
        when(client.indices()).thenReturn(indices);
    }

    /* ==================== creación del índice ==================== */

    @Test
    void siElIndiceYaExisteNoSeVuelveACrear() throws IOException {
        when(indices.exists(existsFn())).thenReturn(new BooleanResponse(true));

        indexer.ensureIndex();

        // Recrearlo borraría el histórico ya indexado.
        verify(indices, never()).create(any(CreateIndexRequest.class));
    }

    @Test
    void siNoExisteSeCreaConElMapeoDeLasAccionesDelOperador() throws IOException {
        when(indices.exists(existsFn())).thenReturn(new BooleanResponse(false));
        when(indices.create(any(CreateIndexRequest.class))).thenReturn(mock(CreateIndexResponse.class));

        indexer.ensureIndex();

        ArgumentCaptor<CreateIndexRequest> captor = ArgumentCaptor.forClass(CreateIndexRequest.class);
        verify(indices).create(captor.capture());
        assertThat(captor.getValue().index()).isEqualTo(INDEX);
        assertThat(captor.getValue().mappings()).isNotNull();
        assertThat(captor.getValue().mappings().properties()).containsKeys("operatorSubject", "operatorEmail",
                "operatorName", "orderId", "orderNumber", "action", "commissionCnyCents", "itemCount", "processedAt");
    }

    @Test
    void unFalloDeOpenSearchAlAsegurarElIndiceNoTumbaElArranque() throws IOException {
        when(indices.exists(existsFn())).thenThrow(new IOException("opensearch caído"));

        assertThatCode(() -> indexer.ensureIndex()).doesNotThrowAnyException();
    }

    /* ==================== indexado ==================== */

    @Test
    void laAccionSeIndexaConSuIdComoIdentificadorDelDocumento() throws IOException {
        OperatorOrderActionEntity action = action();
        when(client.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        indexer.index(action);

        IndexRequest<Map<String, Object>> req = capturedIndexRequest();
        assertThat(req.index()).isEqualTo(INDEX);
        // El id del documento es el de la fila: reindexar la misma acción actualiza, no duplica.
        assertThat(req.id()).isEqualTo(action.getId().toString());
        assertThat(req.document()).containsEntry("operatorSubject", "op-1")
                .containsEntry("operatorEmail", "op@example.com")
                .containsEntry("orderId", action.getOrderId().toString())
                .containsEntry("orderNumber", "NX-100")
                .containsEntry("action", "DELIVERED")
                .containsEntry("commissionCnyCents", 1500L)
                .containsEntry("itemCount", 3)
                .containsEntry("processedAt", action.getProcessedAt().toString());
    }

    @Test
    void unaAccionSinPedidoNiFechaSeIndexaConNulosEnEsosCampos() throws IOException {
        OperatorOrderActionEntity action = action();
        action.setOrderId(null);
        action.setProcessedAt(null);
        when(client.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        indexer.index(action);

        Map<String, Object> doc = capturedIndexRequest().document();
        assertThat(doc).containsEntry("orderId", null).containsEntry("processedAt", null);
    }

    @Test
    void unFalloAlIndexarNoBloqueaElCobroDeLaComision() throws IOException {
        when(client.index(any(IndexRequest.class))).thenThrow(new IOException("opensearch caído"));

        // Postgres ya tiene la acción: si esto propagara, revertiría una comisión ya ganada.
        assertThatCode(() -> indexer.index(action())).doesNotThrowAnyException();
    }

    /* ==================== consulta ==================== */

    @Test
    void sinOperadorLaConsultaFiltraSoloPorRangoDeFechas() throws IOException {
        stubSearch(List.of(), 0L);

        indexer.search("   ", Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-31T00:00:00Z"), 2, 25);

        SearchRequest req = capturedSearchRequest();
        assertThat(req.index()).containsExactly(INDEX);
        assertThat(req.from()).isEqualTo(50); // page 2 * size 25
        assertThat(req.size()).isEqualTo(25);
        assertThat(req.query().isRange()).isTrue();
        assertThat(req.sort().get(0).field().field()).isEqualTo("processedAt");
        // Más recientes primero: es un histórico, lo último procesado es lo que se mira.
        assertThat(req.sort().get(0).field().order()).isEqualTo(SortOrder.Desc);
    }

    @Test
    void conOperadorLaConsultaExigeFechaYSujetoALaVez() throws IOException {
        stubSearch(List.of(), 0L);

        indexer.search("op-1", Instant.EPOCH, Instant.EPOCH.plusSeconds(1), 0, 10);

        SearchRequest req = capturedSearchRequest();
        assertThat(req.query().isBool()).isTrue();
        // Los dos filtros van en must: un operador no puede ver acciones fuera de su rango ni ajenas.
        assertThat(req.query().bool().must()).hasSize(2);
    }

    @Test
    void laConsultaDevuelveLosDocumentosConSuTotalYPaginacion() throws IOException {
        stubSearch(List.of(Map.<String, Object>of("action", "DELIVERED"), Map.of("action", "SHIPPED")), 7L);

        Map<String, Object> out = indexer.search(null, Instant.EPOCH, Instant.EPOCH.plusSeconds(1), 1, 2);

        assertThat(out).containsEntry("total", 7L).containsEntry("page", 1).containsEntry("size", 2);
        assertThat((List<?>) out.get("items")).hasSize(2);
    }

    @Test
    void siOpenSearchNoInformaElTotalSeUsaElNumeroDeResultados() throws IOException {
        stubSearch(List.of(Map.<String, Object>of("action", "DELIVERED")), null);

        Map<String, Object> out = indexer.search(null, Instant.EPOCH, Instant.EPOCH.plusSeconds(1), 0, 10);

        assertThat(out).containsEntry("total", 1L);
    }

    @Test
    void losResultadosSinDocumentoSeDescartan() throws IOException {
        stubSearch(Arrays.asList(Map.<String, Object>of("action", "DELIVERED"), null), 2L);

        Map<String, Object> out = indexer.search(null, Instant.EPOCH, Instant.EPOCH.plusSeconds(1), 0, 10);

        // El total lo manda OpenSearch; la lista solo lleva los hits con _source utilizable.
        assertThat((List<?>) out.get("items")).hasSize(1);
        assertThat(out).containsEntry("total", 2L);
    }

    /* ==================== helpers ==================== */

    private static OperatorOrderActionEntity action() {
        OperatorOrderActionEntity action = OperatorOrderActionEntity.builder().operatorSubject("op-1")
                .operatorEmail("op@example.com").operatorName("Operador Uno").orderId(UUID.randomUUID())
                .orderNumber("NX-100").action("DELIVERED").commissionCnyCents(1500L).itemCount(3)
                .processedAt(Instant.parse("2026-07-20T12:00:00Z")).build();
        action.setId(UUID.randomUUID());
        return action;
    }

    /** Matcher para el overload {@code exists(Function)} (el otro recibe un ExistsRequest). */
    private static Function<ExistsRequest.Builder, ObjectBuilder<ExistsRequest>> existsFn() {
        return any();
    }

    @SuppressWarnings("unchecked")
    private IndexRequest<Map<String, Object>> capturedIndexRequest() throws IOException {
        ArgumentCaptor<IndexRequest<Map<String, Object>>> captor = ArgumentCaptor.forClass(IndexRequest.class);
        verify(client).index(captor.capture());
        return captor.getValue();
    }

    private SearchRequest capturedSearchRequest() throws IOException {
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(client).search(captor.capture(), eq(Map.class));
        return captor.getValue();
    }

    /** Prepara la respuesta de OpenSearch con los documentos y el total indicados ({@code null} = sin total). */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void stubSearch(List<Map<String, Object>> sources, Long total) throws IOException {
        SearchResponse<Map> response = mock(SearchResponse.class);
        HitsMetadata<Map> hitsMetadata = mock(HitsMetadata.class);
        List<Hit<Map>> hits = sources.stream().map(src -> {
            Hit<Map> hit = mock(Hit.class);
            when(hit.source()).thenReturn(src);
            return hit;
        }).toList();
        when(response.hits()).thenReturn(hitsMetadata);
        when(hitsMetadata.hits()).thenReturn(hits);
        if (total == null) {
            when(hitsMetadata.total()).thenReturn(null);
        } else {
            TotalHits totalHits = mock(TotalHits.class);
            when(totalHits.value()).thenReturn(total);
            when(hitsMetadata.total()).thenReturn(totalHits);
        }
        when(client.search(any(SearchRequest.class), eq(Map.class))).thenReturn(response);
    }
}
