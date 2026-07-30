package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.nexaplatform.dropshipping.domain.model.Wallet;
import com.nexaplatform.dropshipping.domain.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
 * Indexado de monederos para el listado del admin.
 *
 * <p>Dos invariantes: el documento NO lleva el saldo —el dinero se lee siempre fresco de la base de datos,
 * un saldo cacheado en el índice se quedaría viejo en cuanto hubiera un cargo— y nada de lo que hace este
 * indexador puede romper la escritura que lo dispara: si OpenSearch está caído, se traga el error.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov09WalletIndexerTest {

    private static final String INDICE = "wallets";

    @Mock
    OpenSearchClient client;
    @Mock
    OpenSearchIndicesClient indices;
    @Mock
    WalletRepository walletRepository;
    @Mock
    WalletSearchService walletSearchService;

    private WalletIndexer indexer;

    @BeforeEach
    void buildSubject() throws Exception {
        indexer = new WalletIndexer(client, walletRepository, walletSearchService);
        // El nombre del índice viene de un @Value: sin Spring quedaría a null y todo iría al índice "null".
        Field f = WalletIndexer.class.getDeclaredField("index");
        f.setAccessible(true);
        f.set(indexer, INDICE);
    }

    private static Wallet wallet(UUID id) {
        Wallet w = new Wallet();
        w.setId(id);
        w.setUserEmail("ada@example.com");
        w.setUserName("Ada Lovelace");
        w.setCurrencyDefault("usd");
        w.setStatus("ACTIVE");
        w.setBalanceUsdCents(123456);
        w.setCreatedAt(Instant.parse("2026-01-02T03:04:05Z"));
        return w;
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<IndexRequest<Map<String, Object>>> captorDeIndexado() {
        return ArgumentCaptor.forClass(IndexRequest.class);
    }

    /** Matcher tipado para el overload de lambda de {@code indices().exists(...)}. */
    private static Function<ExistsRequest.Builder, ObjectBuilder<ExistsRequest>> anyExistsLambda() {
        return any();
    }

    /** Matcher tipado para el overload de lambda de {@code client.delete(...)}. */
    private static Function<DeleteRequest.Builder, ObjectBuilder<DeleteRequest>> anyDeleteLambda() {
        return any();
    }

    // ---------------------------------------------------------------- creación del índice

    @Test
    void unIndiceQueYaExisteNoSeVuelveACrear() throws IOException {
        // Recrearlo borraría todos los documentos y dejaría el listado del admin en blanco.
        when(client.indices()).thenReturn(indices);
        when(indices.exists(anyExistsLambda())).thenReturn(new BooleanResponse(true));

        indexer.ensureIndex();

        verify(indices, never()).create(any(CreateIndexRequest.class));
    }

    @Test
    void siNoExisteSeCreaConEstadoYDivisaComoTerminoExacto() throws IOException {
        // status/currency se filtran por igualdad y createdAt ordena: como texto analizado no filtrarían.
        when(client.indices()).thenReturn(indices);
        when(indices.exists(anyExistsLambda())).thenReturn(new BooleanResponse(false));
        when(indices.create(any(CreateIndexRequest.class))).thenReturn(mock(CreateIndexResponse.class));

        indexer.ensureIndex();

        ArgumentCaptor<CreateIndexRequest> captor = ArgumentCaptor.forClass(CreateIndexRequest.class);
        verify(indices).create(captor.capture());
        assertThat(captor.getValue().index()).isEqualTo(INDICE);
        assertThat(captor.getValue().mappings().properties().get("status").isKeyword()).isTrue();
        assertThat(captor.getValue().mappings().properties().get("currency").isKeyword()).isTrue();
        assertThat(captor.getValue().mappings().properties().get("createdAt").isDate()).isTrue();
        assertThat(captor.getValue().mappings().properties().get("userEmail").isText()).isTrue();
    }

    @Test
    void unOpenSearchCaidoAlArrancarNoImpideQueLaAplicacionLevante() throws IOException {
        when(client.indices()).thenReturn(indices);
        when(indices.exists(anyExistsLambda())).thenThrow(new IOException("opensearch down"));

        assertThatCode(() -> indexer.ensureIndex()).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- arranque

    @Test
    void elArranqueRellenaElIndiceSiEstaVacio() throws IOException {
        when(walletSearchService.pageIds(null, null, null, 0, 1)).thenReturn(Optional.empty());
        when(walletRepository.findAll()).thenReturn(List.of(wallet(UUID.randomUUID())));
        when(client.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        indexer.warmUpOnStartup();

        verify(client, times(1)).index(any(IndexRequest.class));
    }

    @Test
    void elArranqueNoReindexaSiElIndiceYaTieneDatos() {
        // Reindexar en cada arranque con miles de monederos alargaría el despliegue sin ganar nada.
        when(walletSearchService.pageIds(null, null, null, 0, 1))
                .thenReturn(Optional.of(new WalletSearchService.IdPage(List.of(UUID.randomUUID()), 1)));

        indexer.warmUpOnStartup();

        verify(walletRepository, never()).findAll();
    }

    @Test
    void siLaConsultaDeArranqueFallaLaAplicacionSigueArrancando() {
        when(walletSearchService.pageIds(null, null, null, 0, 1)).thenThrow(new IllegalStateException("down"));

        assertThatCode(() -> indexer.warmUpOnStartup()).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- documento indexado

    @Test
    void elDocumentoLlevaLosCamposDeBusquedaPeroNuncaElSaldo() throws IOException {
        // El saldo se lee siempre de la base de datos: cachearlo aquí mostraría dinero desactualizado.
        UUID id = UUID.randomUUID();
        when(client.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        indexer.indexWallet(wallet(id));

        ArgumentCaptor<IndexRequest<Map<String, Object>>> captor = captorDeIndexado();
        verify(client).index(captor.capture());
        assertThat(captor.getValue().index()).isEqualTo(INDICE);
        assertThat(captor.getValue().id()).isEqualTo(id.toString());
        Map<String, Object> doc = captor.getValue().document();
        assertThat(doc).containsEntry("id", id.toString())
                .containsEntry("userEmail", "ada@example.com")
                .containsEntry("userName", "Ada Lovelace")
                .containsEntry("status", "ACTIVE")
                // La divisa se normaliza a mayúsculas: el filtro del admin compara por término exacto.
                .containsEntry("currency", "USD")
                .containsEntry("createdAt", "2026-01-02T03:04:05Z");
        assertThat(doc).doesNotContainKeys("balanceUsdCents", "balance", "holdUsdCents");
    }

    @Test
    void unMonederoSinDivisaNiFechaSeIndexaConNulosYNoRevienta() throws IOException {
        UUID id = UUID.randomUUID();
        Wallet w = new Wallet();
        w.setId(id);

        indexer.indexWallet(w);

        ArgumentCaptor<IndexRequest<Map<String, Object>>> captor = captorDeIndexado();
        verify(client).index(captor.capture());
        assertThat(captor.getValue().document()).containsEntry("currency", null)
                .containsEntry("createdAt", null);
    }

    @Test
    void unMonederoNuloOSinIdentificadorNiSeIndexa() throws IOException {
        indexer.indexWallet(null);
        indexer.indexWallet(new Wallet());

        verify(client, never()).index(any(IndexRequest.class));
    }

    @Test
    void unFalloAlIndexarNoRompeLaEscrituraQueLoDisparo() throws IOException {
        when(client.index(any(IndexRequest.class))).thenThrow(new IOException("down"));

        assertThatCode(() -> indexer.indexWallet(wallet(UUID.randomUUID()))).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- borrado y reindexado

    @Test
    void borrarDelIndiceApuntaAlIndiceYAlIdentificadorDelMonedero() throws IOException {
        UUID id = UUID.randomUUID();

        indexer.deleteFromIndex(id);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Function<DeleteRequest.Builder, ObjectBuilder<DeleteRequest>>> captor =
                ArgumentCaptor.forClass((Class) Function.class);
        verify(client).delete(captor.capture());
        DeleteRequest req = captor.getValue().apply(new DeleteRequest.Builder()).build();
        assertThat(req.index()).isEqualTo(INDICE);
        assertThat(req.id()).isEqualTo(id.toString());
    }

    @Test
    void unFalloBorrandoDelIndiceTampocoSePropaga() throws IOException {
        when(client.delete(anyDeleteLambda())).thenThrow(new IOException("down"));

        assertThatCode(() -> indexer.deleteFromIndex(UUID.randomUUID())).doesNotThrowAnyException();
    }

    @Test
    void elReindexadoCompletoDevuelveCuantosMonederosHaIndexado() throws IOException {
        when(walletRepository.findAll()).thenReturn(List.of(wallet(UUID.randomUUID()), wallet(UUID.randomUUID())));
        when(client.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        assertThat(indexer.reindexAll()).isEqualTo(2);

        verify(client, times(2)).index(any(IndexRequest.class));
    }

    @Test
    void unMonederoQueFallaAlIndexarNoDetieneElReindexadoDelResto() throws IOException {
        // Si un documento roto abortase el pase, el resto del listado se quedaría sin indexar.
        when(walletRepository.findAll()).thenReturn(List.of(wallet(UUID.randomUUID()), wallet(UUID.randomUUID())));
        when(client.index(any(IndexRequest.class))).thenThrow(new IOException("down"));

        // Se intentan los dos —el fallo del primero no corta el pase— pero el recuento son los que el
        // índice ACEPTÓ: con OpenSearch caído, decir «reindexed 2» con el índice vacío engañaba al admin.
        assertThat(indexer.reindexAll()).isZero();
        verify(client, times(2)).index(any(IndexRequest.class));
    }
}
