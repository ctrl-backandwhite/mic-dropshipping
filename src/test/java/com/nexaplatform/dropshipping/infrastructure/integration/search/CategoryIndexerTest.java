package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryRepository;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryIndexerTest {

    @Mock
    OpenSearchClient client;

    @Mock
    CategoryRepository categoryRepository;

    @Mock
    CategorySearchService categorySearchService;

    @InjectMocks
    CategoryIndexer indexer;

    private static final String INDEX = "categories";

    @BeforeEach
    void setUp() throws Exception {
        java.lang.reflect.Field f = CategoryIndexer.class.getDeclaredField("index");
        f.setAccessible(true);
        f.set(indexer, INDEX);
    }

    private CategoryEntity category(UUID id, CategoryEntity parent) {
        CategoryEntity c = CategoryEntity.builder()
                .slug("electronics")
                .active(true)
                .position(3)
                .icon("bolt")
                .nameZh("电子")
                .parent(parent)
                .build();
        c.setId(id); // BaseEntity id is not part of the @Builder.

        CategoryTranslationEntity es = CategoryTranslationEntity.builder()
                .language("es").name("Electrónica").build();
        es.setCategory(c);
        c.getTranslations().add(es);
        return c;
    }

    @Test
    void indexCategory_buildsDocumentWithLevelAndParentAndIndexes() throws IOException {
        UUID rootId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        CategoryEntity root = category(rootId, null);
        CategoryEntity child = category(childId, root);

        when(categoryRepository.findById(childId)).thenReturn(Optional.of(child));
        when(client.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        indexer.indexCategory(childId);

        ArgumentCaptor<IndexRequest<Map<String, Object>>> captor = captor();
        verify(client).index(captor.capture());
        IndexRequest<Map<String, Object>> req = captor.getValue();

        assertThat(req.index()).isEqualTo(INDEX);
        assertThat(req.id()).isEqualTo(childId.toString());

        Map<String, Object> doc = req.document();
        assertThat(doc).containsEntry("id", childId.toString())
                .containsEntry("slug", "electronics")
                .containsEntry("active", true)
                .containsEntry("position", 3)
                .containsEntry("icon", "bolt")
                .containsEntry("nameZh", "电子")
                .containsEntry("nameEs", "Electrónica")
                .containsEntry("level", 1) // one parent → depth 1
                .containsEntry("parentId", rootId.toString())
                .containsEntry("parentSlug", "electronics");
    }

    @Test
    void indexCategory_rootHasLevelZeroAndNullParent() throws IOException {
        UUID id = UUID.randomUUID();
        CategoryEntity root = category(id, null);

        when(categoryRepository.findById(id)).thenReturn(Optional.of(root));
        when(client.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        indexer.indexCategory(id);

        ArgumentCaptor<IndexRequest<Map<String, Object>>> captor = captor();
        verify(client).index(captor.capture());
        Map<String, Object> doc = captor.getValue().document();
        assertThat(doc).containsEntry("level", 0)
                .containsEntry("parentId", null)
                .containsEntry("parentSlug", null);
    }

    @Test
    void indexCategory_missingCategory_doesNotTouchTheClient() throws IOException {
        UUID id = UUID.randomUUID();
        when(categoryRepository.findById(id)).thenReturn(Optional.empty());

        indexer.indexCategory(id);

        verify(client, never()).index(any(IndexRequest.class));
    }

    @Test
    void indexCategory_swallowsClientFailure() throws IOException {
        UUID id = UUID.randomUUID();
        CategoryEntity root = category(id, null);
        when(categoryRepository.findById(id)).thenReturn(Optional.of(root));
        when(client.index(any(IndexRequest.class))).thenThrow(new IOException("down"));

        assertThatCode(() -> indexer.indexCategory(id)).doesNotThrowAnyException();
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
    void reindexAll_indexesEveryCategoryAndReturnsCount() throws IOException {
        CategoryEntity c1 = category(UUID.randomUUID(), null);
        CategoryEntity c2 = category(UUID.randomUUID(), null);
        when(categoryRepository.findAll()).thenReturn(List.of(c1, c2));
        when(client.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        int count = indexer.reindexAll();

        assertThat(count).isEqualTo(2);
        verify(client, times(2)).index(any(IndexRequest.class));
    }

    @Test
    void warmUpOnStartup_reindexesWhenIndexEmpty() throws IOException {
        when(categorySearchService.listFromIndex(null)).thenReturn(Optional.empty());
        CategoryEntity c = category(UUID.randomUUID(), null);
        when(categoryRepository.findAll()).thenReturn(List.of(c));
        when(client.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        indexer.warmUpOnStartup();

        verify(client, times(1)).index(any(IndexRequest.class));
    }

    @Test
    void warmUpOnStartup_skipsReindexWhenIndexAlreadyPopulated() {
        when(categorySearchService.listFromIndex(null))
                .thenReturn(Optional.of(List.of(
                        new CategorySearchService.IndexedCategory(UUID.randomUUID(), "s", null, "es", null, null,
                                null, 0, true, null))));

        indexer.warmUpOnStartup();

        verify(categoryRepository, never()).findAll();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<IndexRequest<Map<String, Object>>> captor() {
        return ArgumentCaptor.forClass(IndexRequest.class);
    }
}
