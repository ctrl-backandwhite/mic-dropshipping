package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupplierRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
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
import org.opensearch.client.opensearch.core.DeleteRequest;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.IndexResponse;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.CreateIndexResponse;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;
import org.opensearch.client.transport.endpoints.BooleanResponse;
import org.opensearch.client.util.ObjectBuilder;

import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El indexador de proveedores es "best-effort": que OpenSearch esté caído no puede romper ninguna
 * escritura del catálogo. Además el documento lleva embebido el número de productos, que no está en la
 * tabla de proveedores y hay que recalcular en cada indexación.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov10SupplierIndexerTest {

    private static final String INDEX = "suppliers";

    @Mock
    OpenSearchClient client;
    @Mock
    OpenSearchIndicesClient indices;
    @Mock
    SupplierRepository supplierRepository;
    @Mock
    SupplierSearchService supplierSearchService;
    @Mock
    EntityManager em;

    @InjectMocks
    SupplierIndexer indexer;

    @BeforeEach
    void setUp() throws Exception {
        // Ni el @Value del índice ni el @PersistenceContext los inyecta Mockito (el constructor de
        // Lombok solo cubre los campos final): sin ellos todo iría al índice "null" y el conteo sería NPE.
        set("index", INDEX);
        set("em", em);
    }

    private void set(String fieldName, Object value) throws Exception {
        Field f = SupplierIndexer.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(indexer, value);
    }

    /* ---------- creación del índice ---------- */

    @Test
    void unIndiceQueYaExisteNoSeVuelveACrear() throws IOException {
        when(client.indices()).thenReturn(indices);
        when(indices.exists(anyExistsLambda())).thenReturn(new BooleanResponse(true));

        indexer.ensureIndex();

        // Recrearlo borraría el mapeo (y con él el orden y los filtros del listado).
        verify(indices, never()).create(any(CreateIndexRequest.class));
    }

    @Test
    void siElIndiceNoExisteSeCreaConElMapeoDeFiltrosYOrdenacion() throws IOException {
        when(client.indices()).thenReturn(indices);
        when(indices.exists(anyExistsLambda())).thenReturn(new BooleanResponse(false));
        when(indices.create(any(CreateIndexRequest.class))).thenReturn(mock(CreateIndexResponse.class));

        indexer.ensureIndex();

        ArgumentCaptor<CreateIndexRequest> captor = ArgumentCaptor.forClass(CreateIndexRequest.class);
        verify(indices).create(captor.capture());
        CreateIndexRequest req = captor.getValue();
        assertThat(req.index()).isEqualTo(INDEX);
        // country/verified se filtran por término exacto y rating/productCount se ordenan: si salieran
        // como texto analizado, ni el filtro ni el orden del listado funcionarían.
        assertThat(req.mappings().properties().get("country").isKeyword()).isTrue();
        assertThat(req.mappings().properties().get("verified").isBoolean()).isTrue();
        assertThat(req.mappings().properties().get("rating").isDouble()).isTrue();
        assertThat(req.mappings().properties().get("productCount").isInteger()).isTrue();
        assertThat(req.mappings().properties().get("name").isText()).isTrue();
    }

    @Test
    void unOpenSearchCaidoAlArrancarNoImpideQueLaAplicacionLevante() throws IOException {
        when(client.indices()).thenReturn(indices);
        when(indices.exists(anyExistsLambda())).thenReturn(new BooleanResponse(false));
        when(indices.create(any(CreateIndexRequest.class))).thenThrow(new IOException("opensearch down"));

        assertThatCode(() -> indexer.ensureIndex()).doesNotThrowAnyException();
    }

    /* ---------- arranque ---------- */

    @Test
    void elArranqueRellenaElIndiceCuandoEstaVacio() throws IOException {
        when(supplierSearchService.listFromIndex(null)).thenReturn(Optional.empty());
        when(supplierRepository.findAll()).thenReturn(List.of(proveedor()));
        stubConteoAgrupado(List.of());
        when(client.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        indexer.warmUpOnStartup();

        verify(client, times(1)).index(any(IndexRequest.class));
    }

    @Test
    void elArranqueNoReindexaSiElIndiceYaTieneDatos() {
        when(supplierSearchService.listFromIndex(null)).thenReturn(Optional.of(List.of(indexado())));

        indexer.warmUpOnStartup();

        verify(supplierRepository, never()).findAll();
    }

    @Test
    void unFalloAlComprobarElIndiceNoTumbaElArranque() {
        when(supplierSearchService.listFromIndex(null)).thenThrow(new IllegalStateException("opensearch down"));

        assertThatCode(() -> indexer.warmUpOnStartup()).doesNotThrowAnyException();
        verify(supplierRepository, never()).findAll();
    }

    /* ---------- indexación ---------- */

    @Test
    void elDocumentoLlevaElNumeroDeProductosRecalculado() throws IOException {
        SupplierEntity s = proveedor();
        when(supplierRepository.findById(s.getId())).thenReturn(Optional.of(s));
        stubConteoSimple(s.getId(), 42L);
        when(client.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        indexer.indexSupplier(s.getId());

        ArgumentCaptor<IndexRequest<Map<String, Object>>> captor = captor();
        verify(client).index(captor.capture());
        assertThat(captor.getValue().index()).isEqualTo(INDEX);
        assertThat(captor.getValue().id()).isEqualTo(s.getId().toString());
        Map<String, Object> doc = captor.getValue().document();
        assertThat(doc).containsEntry("id", s.getId().toString()).containsEntry("externalId", "sup-1688")
                .containsEntry("source", "1688").containsEntry("name", "Fábrica X").containsEntry("nameZh", "工厂")
                .containsEntry("country", "CN").containsEntry("city", "Yiwu").containsEntry("yearsActive", 7)
                .containsEntry("verified", true).containsEntry("trustPass", false)
                // productCount no está en la tabla: si no se recalculara, el listado mostraría 0.
                .containsEntry("productCount", 42L);
        assertThat(doc.get("createdAt")).isNotNull();
    }

    @Test
    void unProveedorQueYaNoExisteNoSeIndexa() throws IOException {
        UUID id = UUID.randomUUID();
        when(supplierRepository.findById(id)).thenReturn(Optional.empty());

        indexer.indexSupplier(id);

        verify(client, never()).index(any(IndexRequest.class));
    }

    @Test
    void unProveedorSinFechaDeAltaSeIndexaConCreatedAtNulo() throws IOException {
        SupplierEntity s = proveedor();
        s.setCreatedAt(null);
        when(supplierRepository.findById(s.getId())).thenReturn(Optional.of(s));
        stubConteoSimple(s.getId(), 0L);
        when(client.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        indexer.indexSupplier(s.getId());

        ArgumentCaptor<IndexRequest<Map<String, Object>>> captor = captor();
        verify(client).index(captor.capture());
        assertThat(captor.getValue().document()).containsEntry("createdAt", null);
    }

    @Test
    void unFalloDeIndexacionNoPropagaALaEscrituraDelCatalogo() throws IOException {
        SupplierEntity s = proveedor();
        when(supplierRepository.findById(s.getId())).thenReturn(Optional.of(s));
        stubConteoSimple(s.getId(), 1L);
        when(client.index(any(IndexRequest.class))).thenThrow(new IOException("down"));

        assertThatCode(() -> indexer.indexSupplier(s.getId())).doesNotThrowAnyException();
    }

    @Test
    void elBorradoDelIndiceApuntaAlIndiceYAlIdCorrectos() throws IOException {
        UUID id = UUID.randomUUID();

        indexer.deleteFromIndex(id);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Function<DeleteRequest.Builder, ObjectBuilder<DeleteRequest>>> captor = ArgumentCaptor
                .forClass((Class) Function.class);
        verify(client).delete(captor.capture());
        DeleteRequest req = captor.getValue().apply(new DeleteRequest.Builder()).build();
        assertThat(req.index()).isEqualTo(INDEX);
        assertThat(req.id()).isEqualTo(id.toString());
    }

    @Test
    void unFalloAlBorrarDelIndiceNoPropaga() throws IOException {
        when(client.delete(any(DeleteRequest.class))).thenThrow(new IOException("down"));

        assertThatCode(() -> indexer.deleteFromIndex(UUID.randomUUID())).doesNotThrowAnyException();
    }

    @Test
    void laReindexacionCompletaUsaUnSoloConteoParaTodosLosProveedores() throws IOException {
        SupplierEntity conProductos = proveedor();
        SupplierEntity sinProductos = proveedor();
        when(supplierRepository.findAll()).thenReturn(List.of(conProductos, sinProductos));
        stubConteoAgrupado(List.<Object[]>of(new Object[]{conProductos.getId(), 9L}));
        when(client.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        int n = indexer.reindexAll();

        assertThat(n).isEqualTo(2);
        ArgumentCaptor<IndexRequest<Map<String, Object>>> captor = captor();
        verify(client, times(2)).index(captor.capture());
        assertThat(captor.getAllValues().get(0).document()).containsEntry("productCount", 9L);
        // El que no aparece en el GROUP BY tiene 0, no null (null rompería la ordenación por número).
        assertThat(captor.getAllValues().get(1).document()).containsEntry("productCount", 0L);
        // Un COUNT por proveedor (N+1) haría inviable la reindexación completa.
        verify(em, never()).createQuery(anyString(), eq(Long.class));
    }

    /* ---------- utilidades ---------- */

    private static SupplierEntity proveedor() {
        SupplierEntity s = SupplierEntity.builder().externalId("sup-1688").source("1688").name("Fábrica X").nameZh("工厂")
                .country("CN").city("Yiwu").rating(new BigDecimal("4.8")).yearsActive(7).verified(true).trustPass(false)
                .build();
        s.setId(UUID.randomUUID()); // el id de BaseEntity no entra en el @Builder
        s.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return s;
    }

    private static SupplierSearchService.IndexedSupplier indexado() {
        return new SupplierSearchService.IndexedSupplier(UUID.randomUUID(), "sup-1688", "Fábrica X", "工厂", "CN", "Yiwu",
                null, null, false, false, 0);
    }

    private void stubConteoAgrupado(List<Object[]> rows) {
        Query query = mock(Query.class);
        when(em.createQuery(contains("GROUP BY"))).thenReturn(query);
        doReturn(rows).when(query).getResultList();
    }

    @SuppressWarnings("unchecked")
    private void stubConteoSimple(UUID id, long count) {
        TypedQuery<Long> typed = mock(TypedQuery.class);
        when(em.createQuery(anyString(), eq(Long.class))).thenReturn(typed);
        when(typed.setParameter("id", id)).thenReturn(typed);
        when(typed.getSingleResult()).thenReturn(count);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<IndexRequest<Map<String, Object>>> captor() {
        return ArgumentCaptor.forClass(IndexRequest.class);
    }

    /** Matcher tipado para el overload de lambda de {@code indices().exists(...)}. */
    private static Function<ExistsRequest.Builder, ObjectBuilder<ExistsRequest>> anyExistsLambda() {
        return any();
    }
}
