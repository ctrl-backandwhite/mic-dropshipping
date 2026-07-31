package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.config.PersistenceITBase;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryTranslationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT de persistencia de {@code CategoryRepository} / {@code CategoryJpaRepositoryAdapter} contra
 * Postgres real (Testcontainers). Verifica los finders de jerarquía y los {@code @Query} custom
 * frente al motor real, donde el JPQL falla en silencio respecto a H2.
 *
 * Se persiste una jerarquía (root + 2 hijas) directamente con entidades {@code @Builder} porque
 * {@code @DataJpaTest} no carga mappers ni servicios. Cada test corre en su propia transacción con
 * rollback al terminar.
 */
class CategoryRepositoryIT extends PersistenceITBase {

    @Autowired
    CategoryJpaRepositoryAdapter adapter;

    @Autowired
    CategoryRepository repository;

    private CategoryEntity root;
    private CategoryEntity childB;
    private CategoryEntity childA;

    @BeforeEach
    void seedHierarchy() {
        // Raíz (parent == null).
        root = adapter.saveAndFlush(category("electronica", "ELECTRONICS", "cn-root", 0, null));

        // Dos hijas; se insertan en orden NO ordenado para validar el ORDER BY position.
        childB = adapter.saveAndFlush(category("audio", "AUDIO", "cn-audio", 2, root));
        childA = adapter.saveAndFlush(category("moviles", "PHONES", "cn-phones", 1, root));

        // Traducción para ejercitar findAllWithTranslations + search por nombre traducido.
        CategoryTranslationEntity tr = CategoryTranslationEntity.builder()
                .category(root)
                .language("es")
                .name("Electronica")
                .build();
        root.getTranslations().add(tr);
        adapter.saveAndFlush(root);
    }

    private CategoryEntity category(String slug, String source, String externalId, int position, CategoryEntity parent) {
        return CategoryEntity.builder()
                .slug(slug)              // NOT NULL, unique
                .source(source)
                .externalId(externalId)
                .nameZh("zh-" + slug)
                .position(position)      // NOT NULL (int)
                .active(true)            // NOT NULL (boolean)
                .parent(parent)          // relación @ManyToOne parent_id
                .build();
    }

    @Test
    void findBySlug_returnsMatch() {
        Optional<CategoryEntity> found = adapter.findBySlug("audio");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(childB.getId());
    }

    @Test
    void findBySlug_unknown_isEmpty() {
        assertThat(adapter.findBySlug("does-not-exist")).isEmpty();
    }

    @Test
    void findByParentIsNullOrderByPositionAsc_returnsOnlyRoots() {
        List<CategoryEntity> roots = repository.findByParentIsNullOrderByPositionAsc();

        assertThat(roots)
                .extracting(CategoryEntity::getSlug)
                .containsExactly("electronica");
    }

    @Test
    void findByParentIdOrderByPositionAsc_returnsChildrenOrderedByPosition() {
        List<CategoryEntity> children = repository.findByParent_IdOrderByPositionAsc(root.getId());

        // childA (position 1) antes que childB (position 2) pese a insertarse después.
        assertThat(children)
                .extracting(CategoryEntity::getSlug)
                .containsExactly("moviles", "audio");
    }

    @Test
    void findBySourceAndExternalId_returnsMatch() {
        Optional<CategoryEntity> found = repository.findBySourceAndExternalId("PHONES", "cn-phones");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(childA.getId());
    }

    @Test
    void findAllWithTranslations_loadsTranslationsEagerly() {
        List<CategoryEntity> all = adapter.findAllWithTranslations();

        assertThat(all).extracting(CategoryEntity::getSlug)
                .contains("electronica", "audio", "moviles");

        CategoryEntity loadedRoot = all.stream()
                .filter(c -> "electronica".equals(c.getSlug()))
                .findFirst()
                .orElseThrow();
        assertThat(loadedRoot.getTranslations())
                .extracting(CategoryTranslationEntity::getName)
                .containsExactly("Electronica");
    }

    @Test
    void search_bySlugFragment_isPaginatedAndCaseInsensitive() {
        Page<CategoryEntity> page = adapter.search("MOV", PageRequest.of(0, 10));

        assertThat(page.getContent())
                .extracting(CategoryEntity::getSlug)
                .containsExactly("moviles");
    }

    @Test
    void search_byTranslatedName_matchesViaExistsSubquery() {
        Page<CategoryEntity> page = adapter.search("electronica", PageRequest.of(0, 10));

        assertThat(page.getContent())
                .extracting(CategoryEntity::getSlug)
                .contains("electronica");
    }

    @Test
    void search_byChineseName_matchesNameZh() {
        Page<CategoryEntity> page = adapter.search("zh-audio", PageRequest.of(0, 10));

        assertThat(page.getContent())
                .extracting(CategoryEntity::getSlug)
                .containsExactly("audio");
    }
}
