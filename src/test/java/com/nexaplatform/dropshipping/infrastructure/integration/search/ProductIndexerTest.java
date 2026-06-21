package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.DeleteRequest;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.IndexResponse;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductIndexerTest {

    @Mock
    OpenSearchClient client;

    @Mock
    ProductRepository productRepository;

    @InjectMocks
    ProductIndexer indexer;

    private static final String INDEX = "products";

    @BeforeEach
    void setUp() throws Exception {
        // @Value field is not injected by Mockito; set it by reflection so the index name is known.
        java.lang.reflect.Field f = ProductIndexer.class.getDeclaredField("index");
        f.setAccessible(true);
        f.set(indexer, INDEX);
    }

    private ProductEntity product() {
        SupplierEntity supplier = new SupplierEntity();
        supplier.setId(UUID.randomUUID());

        com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity category =
                new com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity();
        category.setId(UUID.randomUUID());

        ProductEntity p = ProductEntity.builder()
                .slug("cool-widget")
                .source("alibaba")
                .externalId("EXT-1")
                .status(ProductStatus.ACTIVE)
                .titleZh("酷小工具")
                .basePrice(new BigDecimal("12.3400"))
                .trendScore(new BigDecimal("99.0"))
                .monthlySales(42)
                .rating(new BigDecimal("4.5"))
                .supplier(supplier)
                .category(category)
                .build();
        // BaseEntity id is not part of the @Builder → set it afterwards.
        p.setId(UUID.randomUUID());

        ProductTranslationEntity es = ProductTranslationEntity.builder()
                .language("es").title("Aparato chulo").shortDescription("desc es").build();
        es.setProduct(p);
        p.getTranslations().add(es);

        ProductImageEntity mirrored = ProductImageEntity.builder()
                .position(0).sourceUrl("http://src/img.jpg").cdnUrl("http://cdn/img.jpg").build();
        mirrored.setProduct(p);
        p.getImages().add(mirrored);

        return p;
    }

    @Test
    void indexProduct_buildsDocumentAndIndexesWithExpectedIdAndIndex() throws IOException {
        ProductEntity p = product();
        when(productRepository.findWithDetailsById(p.getId())).thenReturn(Optional.of(p));
        when(client.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        indexer.indexProduct(p.getId());

        ArgumentCaptor<IndexRequest<Map<String, Object>>> captor = captor();
        verify(client).index(captor.capture());
        IndexRequest<Map<String, Object>> req = captor.getValue();

        assertThat(req.index()).isEqualTo(INDEX);
        assertThat(req.id()).isEqualTo(p.getId().toString());

        Map<String, Object> doc = req.document();
        assertThat(doc).containsEntry("id", p.getId().toString())
                .containsEntry("slug", "cool-widget")
                .containsEntry("source", "alibaba")
                .containsEntry("externalId", "EXT-1")
                .containsEntry("status", "ACTIVE")
                .containsEntry("titleZh", "酷小工具")
                .containsEntry("titleEs", "Aparato chulo")
                .containsEntry("descriptionEs", "desc es")
                .containsEntry("monthlySales", 42)
                .containsEntry("supplierId", p.getSupplier().getId().toString())
                .containsEntry("categoryId", p.getCategory().getId().toString())
                .containsEntry("hasImage", true)
                .containsEntry("mainImage", "http://cdn/img.jpg");
    }

    @Test
    void indexProduct_marksHasImageFalseWhenNoMirroredImage() throws IOException {
        ProductEntity p = product();
        p.getImages().clear();
        ProductImageEntity notMirrored = ProductImageEntity.builder()
                .position(0).sourceUrl("http://src/only.jpg").build(); // cdnUrl null
        notMirrored.setProduct(p);
        p.getImages().add(notMirrored);

        when(productRepository.findWithDetailsById(p.getId())).thenReturn(Optional.of(p));
        when(client.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        indexer.indexProduct(p.getId());

        ArgumentCaptor<IndexRequest<Map<String, Object>>> captor = captor();
        verify(client).index(captor.capture());
        Map<String, Object> doc = captor.getValue().document();
        assertThat(doc).containsEntry("hasImage", false)
                // falls back to the source url when there is no cdn url
                .containsEntry("mainImage", "http://src/only.jpg");
    }

    @Test
    void indexProduct_missingProduct_doesNotTouchTheClient() throws IOException {
        UUID id = UUID.randomUUID();
        when(productRepository.findWithDetailsById(id)).thenReturn(Optional.empty());

        indexer.indexProduct(id);

        verify(client, org.mockito.Mockito.never()).index(any(IndexRequest.class));
    }

    @Test
    void indexProduct_swallowsClientFailure() throws IOException {
        ProductEntity p = product();
        when(productRepository.findWithDetailsById(p.getId())).thenReturn(Optional.of(p));
        when(client.index(any(IndexRequest.class))).thenThrow(new IOException("opensearch down"));

        // best-effort: the failure must not propagate to the write path.
        assertThatCode(() -> indexer.indexProduct(p.getId())).doesNotThrowAnyException();
    }

    @Test
    void deleteFromIndex_callsDeleteWithIndexAndId() throws IOException {
        UUID id = UUID.randomUUID();
        // El código llama al overload de lambda delete(Function); capturamos la Function y la
        // aplicamos a un Builder real para inspeccionar el DeleteRequest (índice + id).
        indexer.deleteFromIndex(id);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<java.util.function.Function<DeleteRequest.Builder, org.opensearch.client.util.ObjectBuilder<DeleteRequest>>> captor =
                ArgumentCaptor.forClass((Class) java.util.function.Function.class);
        verify(client).delete(captor.capture());
        DeleteRequest req = captor.getValue().apply(new DeleteRequest.Builder()).build();
        assertThat(req.index()).isEqualTo(INDEX);
        assertThat(req.id()).isEqualTo(id.toString());
    }

    @Test
    void deleteFromIndex_swallowsClientFailure() throws IOException {
        UUID id = UUID.randomUUID();
        when(client.delete(any(DeleteRequest.class))).thenThrow(new IOException("down"));

        assertThatCode(() -> indexer.deleteFromIndex(id)).doesNotThrowAnyException();
    }

    @Test
    void reindexAll_indexesEveryProductAndReturnsCount() throws IOException {
        ProductEntity p1 = product();
        ProductEntity p2 = product();
        when(productRepository.findAll()).thenReturn(List.of(p1, p2));
        when(productRepository.findWithDetailsById(p1.getId())).thenReturn(Optional.of(p1));
        when(productRepository.findWithDetailsById(p2.getId())).thenReturn(Optional.of(p2));
        when(client.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        int count = indexer.reindexAll();

        assertThat(count).isEqualTo(2);
        verify(client, org.mockito.Mockito.times(2)).index(any(IndexRequest.class));
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<IndexRequest<Map<String, Object>>> captor() {
        return ArgumentCaptor.forClass(IndexRequest.class);
    }
}
