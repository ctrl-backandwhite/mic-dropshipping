package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.AffiliateJpaRepositoryAdapter;
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
 * Indexación de afiliados en OpenSearch.
 *
 * <p>El índice es solo el "enrutador" del listado del admin (ordenar por alta, filtrar por
 * nombre/email/estado); las filas se recomponen luego desde la base de datos. Por eso es
 * <i>best-effort</i>: que OpenSearch esté caído no puede impedir que alguien se dé de alta como
 * afiliado ni que la aplicación arranque.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov04AffiliateIndexerTest {

    private static final String INDEX = "affiliates";

    @Mock
    OpenSearchClient client;
    @Mock
    OpenSearchIndicesClient indices;
    @Mock
    AffiliateJpaRepositoryAdapter affiliateRepo;
    @Mock
    AffiliateSearchService affiliateSearchService;

    @InjectMocks
    AffiliateIndexer indexer;

    @BeforeEach
    void setUp() throws Exception {
        // El nombre del índice viene de un @Value: sin él todo iría al índice "null".
        Field f = AffiliateIndexer.class.getDeclaredField("index");
        f.setAccessible(true);
        f.set(indexer, INDEX);
        when(client.indices()).thenReturn(indices);
    }

    private static AffiliateEntity afiliado(UserEntity user, String code, String status) {
        AffiliateEntity a = new AffiliateEntity();
        a.setId(UUID.randomUUID());
        a.setUser(user);
        a.setCode(code);
        a.setStatus(status);
        a.setCreatedAt(Instant.parse("2026-07-29T10:15:30Z"));
        return a;
    }

    private static UserEntity usuario(String email, String nombre, String ap1, String ap2) {
        return UserEntity.builder().email(email).firstName(nombre).lastName1(ap1).lastName2(ap2).build();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<IndexRequest<Map<String, Object>>> indexCaptor() {
        return ArgumentCaptor.forClass(IndexRequest.class);
    }

    private static Function<ExistsRequest.Builder, ObjectBuilder<ExistsRequest>> anyExistsLambda() {
        return any();
    }

    // ── Creación del índice ──────────────────────────────────────────────────────────────────────

    @Test
    void unIndiceQueYaExisteNoSeVuelveACrear() throws IOException {
        when(indices.exists(anyExistsLambda())).thenReturn(new BooleanResponse(true));

        indexer.ensureIndex();

        // Recrearlo borraría el mapeo y con él el orden y los filtros del listado de afiliados.
        verify(indices, never()).create(any(CreateIndexRequest.class));
    }

    @Test
    void siNoExisteSeCreaConEstadoExactoFechaOrdenableYTextosBuscables() throws IOException {
        when(indices.exists(anyExistsLambda())).thenReturn(new BooleanResponse(false));
        when(indices.create(any(CreateIndexRequest.class))).thenReturn(mock(CreateIndexResponse.class));

        indexer.ensureIndex();

        ArgumentCaptor<CreateIndexRequest> captor = ArgumentCaptor.forClass(CreateIndexRequest.class);
        verify(indices).create(captor.capture());
        CreateIndexRequest req = captor.getValue();
        assertThat(req.index()).isEqualTo(INDEX);
        // status como keyword (filtro exacto) y createdAt como date (orden por más reciente): si salieran
        // como texto analizado, ni el filtro de estado ni la ordenación del listado funcionarían.
        assertThat(req.mappings().properties().get("status").isKeyword()).isTrue();
        assertThat(req.mappings().properties().get("createdAt").isDate()).isTrue();
        assertThat(req.mappings().properties().get("name").isText()).isTrue();
        assertThat(req.mappings().properties().get("email").isText()).isTrue();
        assertThat(req.mappings().properties().get("code").isText()).isTrue();
    }

    @Test
    void unOpenSearchCaidoAlArrancarNoImpideQueLaAplicacionLevante() throws IOException {
        when(indices.exists(anyExistsLambda())).thenThrow(new IOException("opensearch down"));

        assertThatCode(() -> indexer.ensureIndex()).doesNotThrowAnyException();
    }

    // ── Arranque ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void elArranqueRellenaElIndiceCuandoEstaVacio() throws IOException {
        when(affiliateSearchService.pageIds(null, null, 0, 1)).thenReturn(Optional.empty());
        when(affiliateRepo.findAll()).thenReturn(List.of(afiliado(usuario("a@test", "Ana", "López", null),
                "AF1", "ACTIVE")));
        when(client.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        indexer.warmUpOnStartup();

        verify(client).index(any(IndexRequest.class));
    }

    @Test
    void conElIndicePobladoElArranqueNoBarreLaTablaEntera() {
        when(affiliateSearchService.pageIds(null, null, 0, 1))
                .thenReturn(Optional.of(new AffiliateSearchService.IdPage(List.of(UUID.randomUUID()), 1)));

        indexer.warmUpOnStartup();

        verify(affiliateRepo, never()).findAll();
    }

    @Test
    void siElIndiceNoContestaAlArrancarSeSigueSinReindexar() {
        when(affiliateSearchService.pageIds(null, null, 0, 1)).thenThrow(new IllegalStateException("down"));

        assertThatCode(() -> indexer.warmUpOnStartup()).doesNotThrowAnyException();
    }

    // ── Documento indexado ───────────────────────────────────────────────────────────────────────

    @Test
    void elDocumentoLlevaLosCamposPorLosQueElAdminBuscaYOrdena() throws IOException {
        when(client.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));
        AffiliateEntity a = afiliado(usuario("ana@test", "Ana", "López", "Ruiz"), "AF-1", "PENDING");

        indexer.indexAffiliate(a);

        ArgumentCaptor<IndexRequest<Map<String, Object>>> captor = indexCaptor();
        verify(client).index(captor.capture());
        assertThat(captor.getValue().index()).isEqualTo(INDEX);
        assertThat(captor.getValue().id()).isEqualTo(a.getId().toString());
        assertThat(captor.getValue().document())
                .containsEntry("id", a.getId().toString())
                .containsEntry("email", "ana@test")
                .containsEntry("name", "Ana López Ruiz")
                .containsEntry("code", "AF-1")
                .containsEntry("status", "PENDING")
                .containsEntry("createdAt", "2026-07-29T10:15:30Z");
    }

    @Test
    void unAfiliadoSinNombreSeIndexaConSuEmailParaQueLaBusquedaLoEncuentre() throws IOException {
        when(client.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        indexer.indexAffiliate(afiliado(usuario("solo@test", null, null, null), "AF-2", "ACTIVE"));

        ArgumentCaptor<IndexRequest<Map<String, Object>>> captor = indexCaptor();
        verify(client).index(captor.capture());
        assertThat(captor.getValue().document()).containsEntry("name", "solo@test");
    }

    @Test
    void losEspaciosDeUnNombreIncompletoNoSeCuelanEnElIndice() throws IOException {
        // "Ana" + "" + "Ruiz" no puede indexarse como "Ana  Ruiz": la búsqueda por nombre fallaría.
        when(client.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        indexer.indexAffiliate(afiliado(usuario("ana@test", "Ana", null, "Ruiz"), "AF-3", "ACTIVE"));

        ArgumentCaptor<IndexRequest<Map<String, Object>>> captor = indexCaptor();
        verify(client).index(captor.capture());
        assertThat(captor.getValue().document()).containsEntry("name", "Ana Ruiz");
    }

    @Test
    void unAfiliadoSinUsuarioAsociadoSeIndexaIgualSinNombreNiEmail() throws IOException {
        when(client.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        indexer.indexAffiliate(afiliado(null, "AF-4", "ACTIVE"));

        ArgumentCaptor<IndexRequest<Map<String, Object>>> captor = indexCaptor();
        verify(client).index(captor.capture());
        assertThat(captor.getValue().document()).containsEntry("name", null).containsEntry("email", null);
    }

    @Test
    void unAfiliadoNuloOSinIdNoSeIndexa() throws IOException {
        indexer.indexAffiliate((AffiliateEntity) null);
        indexer.indexAffiliate(new AffiliateEntity());

        verify(client, never()).index(any(IndexRequest.class));
    }

    @Test
    void unFalloDeIndexacionNoRompeElAltaDelAfiliado() throws IOException {
        when(client.index(any(IndexRequest.class))).thenThrow(new IOException("down"));
        AffiliateEntity a = afiliado(usuario("a@test", "Ana", null, null), "AF-5", "ACTIVE");

        assertThatCode(() -> indexer.indexAffiliate(a)).doesNotThrowAnyException();
    }

    @Test
    void indexarPorIdInexistenteNoTocaElCliente() throws IOException {
        UUID id = UUID.randomUUID();
        when(affiliateRepo.findById(id)).thenReturn(Optional.empty());

        indexer.indexAffiliate(id);

        verify(client, never()).index(any(IndexRequest.class));
    }

    @Test
    void reindexarDevuelveCuantosAfiliadosSeIndexaron() throws IOException {
        when(affiliateRepo.findAll()).thenReturn(List.of(
                afiliado(usuario("a@test", "Ana", null, null), "AF-1", "ACTIVE"),
                afiliado(usuario("b@test", "Bea", null, null), "AF-2", "ACTIVE")));
        when(client.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        assertThat(indexer.reindexAll()).isEqualTo(2);
        verify(client, times(2)).index(any(IndexRequest.class));
    }

    @Test
    void borrarDelIndiceUsaElIndiceYElIdDelAfiliado() throws IOException {
        UUID id = UUID.randomUUID();

        indexer.deleteFromIndex(id);

        @SuppressWarnings({ "unchecked", "rawtypes" })
        ArgumentCaptor<Function<DeleteRequest.Builder, ObjectBuilder<DeleteRequest>>> captor =
                ArgumentCaptor.forClass((Class) Function.class);
        verify(client).delete(captor.capture());
        DeleteRequest req = captor.getValue().apply(new DeleteRequest.Builder()).build();
        assertThat(req.index()).isEqualTo(INDEX);
        assertThat(req.id()).isEqualTo(id.toString());
    }

    @Test
    void unFalloBorrandoDelIndiceNoRompeElBorradoDelAfiliado() throws IOException {
        when(client.delete(any(DeleteRequest.class))).thenThrow(new IOException("down"));

        assertThatCode(() -> indexer.deleteFromIndex(UUID.randomUUID())).doesNotThrowAnyException();
    }
}
