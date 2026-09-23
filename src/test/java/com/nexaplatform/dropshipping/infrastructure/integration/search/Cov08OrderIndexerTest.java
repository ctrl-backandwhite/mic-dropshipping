package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.search.OrderSearchService.IdPage;
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
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.IndexResponse;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;
import org.opensearch.client.transport.endpoints.BooleanResponse;
import org.opensearch.client.util.ObjectBuilder;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Indexación de pedidos para el listado del panel.
 *
 * <p>El índice solo guarda los campos de RUTA (número, estado, fecha de orden, nombre de envío): es lo
 * que permite paginar y filtrar sin recorrer la tabla entera. Es una vía best-effort: si OpenSearch está
 * caído, indexar no puede tumbar el guardado del pedido, porque entonces se perdería una venta por un
 * problema de un índice que es solo una caché de búsqueda.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov08OrderIndexerTest {

    private static final String INDEX = "orders";

    @Mock
    OpenSearchClient client;
    @Mock
    OrderRepository orderRepository;
    @Mock
    OrderSearchService orderSearchService;
    @InjectMocks
    OrderIndexer indexer;

    @BeforeEach
    void setUp() throws Exception {
        // El campo @Value no lo inyecta Mockito: se fija por reflexión para conocer el nombre del índice.
        Field field = OrderIndexer.class.getDeclaredField("index");
        field.setAccessible(true);
        field.set(indexer, INDEX);
    }

    private static Order order(OrderStatus status, Instant placedAt, Instant createdAt) {
        Order o = new Order();
        o.setId(UUID.randomUUID());
        o.setOrderNumber("NX-IDX-1");
        o.setExternalOrderId("SHOP-99");
        o.setShippingFullName("Ana Pérez");
        o.setStatus(status);
        o.setPlacedAt(placedAt);
        o.setCreatedAt(createdAt);
        return o;
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<IndexRequest<Map<String, Object>>> indexCaptor() {
        return ArgumentCaptor.forClass(IndexRequest.class);
    }

    // ─────────────────────── documento indexado ───────────────────────

    @Test
    void elDocumentoLlevaSoloLosCamposConLosQueSeBuscaYSeOrdena() throws IOException {
        Order o = order(OrderStatus.PAID, Instant.parse("2026-07-01T10:00:00Z"), null);
        when(orderRepository.findById(o.getId())).thenReturn(Optional.of(o));
        when(client.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        indexer.indexOrder(o.getId());

        ArgumentCaptor<IndexRequest<Map<String, Object>>> captor = indexCaptor();
        verify(client).index(captor.capture());
        assertThat(captor.getValue().index()).isEqualTo(INDEX);
        assertThat(captor.getValue().id()).isEqualTo(o.getId().toString());
        assertThat(captor.getValue().document()).containsEntry("orderNumber", "NX-IDX-1")
                .containsEntry("externalOrderId", "SHOP-99").containsEntry("shippingName", "Ana Pérez")
                .containsEntry("status", "PAID").containsEntry("sortTs", "2026-07-01T10:00:00Z");
    }

    @Test
    void unPedidoSinFechaDeCompraSeOrdenaPorLaDeCreacion() throws IOException {
        // Si quedara sin fecha, el pedido caería al final del listado ordenado por más reciente y el
        // administrador no lo vería nunca.
        Order o = order(OrderStatus.PENDING, null, Instant.parse("2026-06-15T08:00:00Z"));
        when(orderRepository.findById(o.getId())).thenReturn(Optional.of(o));

        indexer.indexOrder(o.getId());

        ArgumentCaptor<IndexRequest<Map<String, Object>>> captor = indexCaptor();
        verify(client).index(captor.capture());
        assertThat(captor.getValue().document()).containsEntry("sortTs", "2026-06-15T08:00:00Z");
    }

    @Test
    void unPedidoSinFechasNiEstadoSeIndexaIgualConEsosCamposVacios() throws IOException {
        Order o = order(null, null, null);
        when(orderRepository.findById(o.getId())).thenReturn(Optional.of(o));

        indexer.indexOrder(o.getId());

        ArgumentCaptor<IndexRequest<Map<String, Object>>> captor = indexCaptor();
        verify(client).index(captor.capture());
        assertThat(captor.getValue().document()).containsEntry("status", null).containsEntry("sortTs", null);
    }

    @Test
    void indexarUnPedidoInexistenteNoLlamaAlIndice() throws IOException {
        UUID id = UUID.randomUUID();
        when(orderRepository.findById(id)).thenReturn(Optional.empty());

        indexer.indexOrder(id);

        verify(client, never()).index(any(IndexRequest.class));
    }

    @Test
    void unFalloDelIndiceNoTumbaElGuardadoDelPedido() throws IOException {
        Order o = order(OrderStatus.PAID, Instant.now(), null);
        when(orderRepository.findById(o.getId())).thenReturn(Optional.of(o));
        when(client.index(any(IndexRequest.class))).thenThrow(new IOException("opensearch caído"));

        assertThatCode(() -> indexer.indexOrder(o.getId())).doesNotThrowAnyException();
    }

    @Test
    void borrarDelIndiceNuncaPropagaUnFallo() {
        UUID id = UUID.randomUUID();

        assertThatCode(() -> indexer.deleteFromIndex(id)).doesNotThrowAnyException();
    }

    @Test
    void reindexarDevuelveCuantosPedidosSeIndexaron() throws IOException {
        when(orderRepository.findAll()).thenReturn(List.of(order(OrderStatus.PAID, Instant.now(), null),
                order(OrderStatus.SHIPPED, Instant.now(), null), order(OrderStatus.DELIVERED, Instant.now(), null)));

        assertThat(indexer.reindexAll()).isEqualTo(3);
        verify(client, times(3)).index(any(IndexRequest.class));
    }

    // ─────────────────────── arranque ───────────────────────

    @Test
    void alArrancarConElIndiceVacioSeReconstruyeSolo() throws IOException {
        // Un índice vacío deja el listado del panel en blanco aunque haya pedidos en la base de datos.
        when(orderSearchService.pageIds(null, null, 0, 1)).thenReturn(Optional.empty());
        when(orderRepository.findAll()).thenReturn(List.of(order(OrderStatus.PAID, Instant.now(), null)));

        indexer.warmUpOnStartup();

        verify(client).index(any(IndexRequest.class));
    }

    @Test
    void alArrancarConElIndicePobladoNoSeReconstruyeNada() {
        // Reindexar en cada arranque castigaría la base de datos sin necesidad.
        when(orderSearchService.pageIds(null, null, 0, 1))
                .thenReturn(Optional.of(new IdPage(List.of(UUID.randomUUID()), 1)));

        indexer.warmUpOnStartup();

        verify(orderRepository, never()).findAll();
    }

    @Test
    void siElIndiceNoRespondeAlArrancarLaAplicacionSigueLevantando() {
        when(orderSearchService.pageIds(null, null, 0, 1)).thenThrow(new IllegalStateException("sin conexión"));

        assertThatCode(() -> indexer.warmUpOnStartup()).doesNotThrowAnyException();
        verify(orderRepository, never()).findAll();
    }

    // ─────────────────────── creación del índice ───────────────────────

    /** Matcher para el {@code exists(Function)} del cliente de índices (evita la ambigüedad de sobrecarga). */
    private static Function<ExistsRequest.Builder, ObjectBuilder<ExistsRequest>> cualquierExists() {
        return any();
    }

    @Test
    void siElIndiceYaExisteNoSeVuelveACrear() throws IOException {
        OpenSearchIndicesClient indices = mock(OpenSearchIndicesClient.class);
        when(client.indices()).thenReturn(indices);
        when(indices.exists(cualquierExists())).thenReturn(new BooleanResponse(true));

        indexer.ensureIndex();

        verify(indices, never()).create(any(CreateIndexRequest.class));
    }

    @Test
    void elIndiceSeCreaConElEstadoComoClaveYLaFechaComoFecha() throws IOException {
        // Si "status" se indexara como texto, el filtro por estado dejaría de ser exacto; y sin el
        // mapeo de fecha, el orden "más reciente primero" sería alfabético.
        OpenSearchIndicesClient indices = mock(OpenSearchIndicesClient.class);
        when(client.indices()).thenReturn(indices);
        when(indices.exists(cualquierExists())).thenReturn(new BooleanResponse(false));

        indexer.ensureIndex();

        ArgumentCaptor<CreateIndexRequest> captor = ArgumentCaptor.forClass(CreateIndexRequest.class);
        verify(indices).create(captor.capture());
        assertThat(captor.getValue().index()).isEqualTo(INDEX);
        assertThat(captor.getValue().mappings().properties()).containsKeys("status", "sortTs", "orderNumber",
                "externalOrderId", "shippingName");
        assertThat(captor.getValue().mappings().properties().get("status").isKeyword()).isTrue();
        assertThat(captor.getValue().mappings().properties().get("sortTs").isDate()).isTrue();
    }

    @Test
    void siNoSePuedeComprobarElIndiceLaAplicacionSigueArrancando() throws IOException {
        OpenSearchIndicesClient indices = mock(OpenSearchIndicesClient.class);
        when(client.indices()).thenReturn(indices);
        when(indices.exists(cualquierExists())).thenThrow(new IOException("sin conexión"));

        assertThatCode(() -> indexer.ensureIndex()).doesNotThrowAnyException();
        verify(indices, never()).create(any(CreateIndexRequest.class));
    }
}
