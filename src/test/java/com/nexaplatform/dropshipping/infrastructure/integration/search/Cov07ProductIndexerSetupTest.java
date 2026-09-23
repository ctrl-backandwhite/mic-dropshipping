package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.DeleteByQueryRequest;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.IndexResponse;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;
import org.opensearch.client.util.ObjectBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Puesta a punto del índice de productos: creación idempotente del índice, consumo del evento de
 * Kafka y purga previa del reindexado (sin ella un producto borrado seguía saliendo en la búsqueda).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov07ProductIndexerSetupTest {

    private static final String INDEX = "products";

    @Mock
    OpenSearchClient client;
    @Mock
    OpenSearchIndicesClient indices;
    @Mock
    ProductRepository productRepository;
    @Mock
    com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductAttributeRepository productAttributeRepository;
    @Mock
    ProductIndexSchema schema;
    /** El indexador arma su TransactionTemplate con él; el doble basta porque aquí no se prueba la transacción. */
    @Mock
    org.springframework.transaction.PlatformTransactionManager gestorDeTransacciones;

    @InjectMocks
    ProductIndexer indexer;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(indexer, "index", INDEX);
        ReflectionTestUtils.setField(indexer, "logicalIndex", "products");
        when(client.indices()).thenReturn(indices);
        when(schema.indexName("products")).thenReturn(INDEX);
        when(productAttributeRepository.findByProduct_Id(any())).thenReturn(List.of());
    }

    /** Índice ya existente: no se recrea (recrearlo lo dejaría vacío) y no hay que reindexar. */
    @Test
    void siElIndiceYaExisteNoSeRecreaNiSeMarcaParaReindexar() throws IOException {
        when(schema.createIfMissing("products")).thenReturn(false);

        indexer.ensureIndex();

        assertThat(indexer.isFreshlyCreated()).isFalse();
        assertThat(indexer.indexName()).isEqualTo(INDEX);
    }

    /** Índice recién creado ⇒ nace vacío: queda marcado para que el arranque lo rellene. */
    @Test
    void unIndiceReciencreadoQuedaMarcadoParaReindexar() throws IOException {
        when(schema.createIfMissing("products")).thenReturn(true);

        indexer.ensureIndex();

        assertThat(indexer.isFreshlyCreated()).isTrue();
    }

    /** Un buscador caído degrada la búsqueda; jamás debe impedir que la aplicación arranque. */
    @Test
    void unFalloDeOpenSearchAlAsegurarElIndiceNoTumbaElArranque() throws IOException {
        when(schema.createIfMissing("products")).thenThrow(new IOException("opensearch caído"));

        assertThatCode(() -> indexer.ensureIndex()).doesNotThrowAnyException();
        assertThat(indexer.isFreshlyCreated()).isFalse();
    }

    /**
     * El listener recibe un MAPA, no el evento tipado: el consumidor está configurado para
     * deserializar siempre a mapa, y declarar el tipo hacía que Spring no supiera convertirlo y que el
     * listener fallara con cada mensaje, reintentando sin fin.
     */
    @Test
    void elEventoDeIngestaIndexaElProductoQueTraeElEvento() throws IOException {
        ProductEntity p = product();
        when(productRepository.findWithDetailsById(p.getId())).thenReturn(Optional.of(p));
        when(client.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        indexer.onProductIngested(Map.of("productId", p.getId().toString(), "slug", "camisa-lino", "source", "1688",
                "externalId", "EXT-1"));

        verify(client).index(any(IndexRequest.class));
    }

    /** Un mensaje ilegible no puede tumbar el listener: se ignora y no se indexa nada. */
    @Test
    void unMensajeSinIdentificadorSeIgnoraSinIndexarNada() throws IOException {
        indexer.onProductIngested(Map.of("source", "1688"));

        verify(client, never()).index(any(IndexRequest.class));
    }

    @Test
    void elReindexadoPurgaElIndiceAntesDeReconstruirlo() throws IOException {
        ProductEntity p = product();
        when(productRepository.findAllIds()).thenReturn(List.of(p.getId()));
        when(productRepository.findWithDetailsById(p.getId())).thenReturn(Optional.of(p));
        when(client.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        assertThat(indexer.reindexAll()).isEqualTo(1);

        // Sin la purga, un producto ya borrado de la BD seguiría apareciendo en la búsqueda.
        InOrder order = inOrder(client);
        order.verify(client).deleteByQuery(deleteByQueryFn());
        order.verify(client).index(any(IndexRequest.class));
    }

    @Test
    void siLaPurgaFallaElReindexadoSigueAdelante() throws IOException {
        ProductEntity p = product();
        when(client.deleteByQuery(deleteByQueryFn())).thenThrow(new IOException("opensearch caído"));
        when(productRepository.findAllIds()).thenReturn(List.of(p.getId()));
        when(productRepository.findWithDetailsById(p.getId())).thenReturn(Optional.of(p));
        when(client.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        assertThat(indexer.reindexAll()).isEqualTo(1);
        verify(client).index(any(IndexRequest.class));
    }

    @Test
    void elIdiomaDeCadaTraduccionDaNombreASuCampoEnElDocumento() throws IOException {
        ProductEntity p = product();
        p.getTranslations().add(translation("pt", "Camisa de linho", "resumo"));
        when(productRepository.findWithDetailsById(p.getId())).thenReturn(Optional.of(p));
        when(client.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        indexer.indexProduct(p.getId());

        ArgumentCaptor<IndexRequest<Map<String, Object>>> captor = captor();
        verify(client).index(captor.capture());
        // El mapeo declara titlePt/titleEs/…: si no se capitalizara el idioma, el campo no existiría.
        // Las descripciones, en cambio, van TODAS a un único campo que solo se consulta como red de
        // seguridad: no compiten con el título en la búsqueda normal.
        assertThat(captor.getValue().document()).containsEntry("titlePt", "Camisa de linho");
        assertThat(captor.getValue().document().get("descAll").toString()).contains("resumo");
    }

    @Test
    void unProductoSinImagenesNoDeclaraImagenPrincipal() throws IOException {
        ProductEntity p = product();
        when(productRepository.findWithDetailsById(p.getId())).thenReturn(Optional.of(p));
        when(client.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        indexer.indexProduct(p.getId());

        ArgumentCaptor<IndexRequest<Map<String, Object>>> captor = captor();
        verify(client).index(captor.capture());
        assertThat(captor.getValue().document()).containsEntry("hasImage", false).doesNotContainKey("mainImage");
    }

    /* ==================== helpers ==================== */

    private static ProductEntity product() {
        ProductEntity p = ProductEntity.builder().slug("camisa-lino").source("1688").externalId("EXT-1").titleZh("亚麻衬衫")
                .build();
        p.setId(UUID.randomUUID());
        return p;
    }

    private static ProductTranslationEntity translation(String language, String title, String shortDescription) {
        return ProductTranslationEntity.builder().language(language).title(title).shortDescription(shortDescription)
                .build();
    }

    /** Matcher para el overload de lambda (el otro recibe la petición ya construida). */
    private static Function<ExistsRequest.Builder, ObjectBuilder<ExistsRequest>> existsFn() {
        return any();
    }

    private static Function<DeleteByQueryRequest.Builder, ObjectBuilder<DeleteByQueryRequest>> deleteByQueryFn() {
        return any();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<IndexRequest<Map<String, Object>>> captor() {
        return ArgumentCaptor.forClass(IndexRequest.class);
    }
}
