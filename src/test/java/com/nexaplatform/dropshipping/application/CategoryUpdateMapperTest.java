package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.mapper.CategoryUpdateMapper;
import com.nexaplatform.dropshipping.domain.model.Category;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La actualización parcial de una categoría solo toca los campos editables (slug, nameZh, position,
 * active, icon, names) y preserva identidad/origen/parentId/productCount/auditoría. Con
 * NullValuePropertyMappingStrategy.IGNORE un campo null en source no pisa el target.
 */
class CategoryUpdateMapperTest {

    private final CategoryUpdateMapper mapper = Mappers.getMapper(CategoryUpdateMapper.class);

    @Test
    void updateFromModel_copiesEditableFieldsAndPreservesIdentityAndReadOnly() {
        UUID id = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        Instant created = Instant.parse("2024-01-01T00:00:00Z");

        Category target = Category.builder().id(id).source("PULL").externalId("ext-1").parentId(parentId)
                .productCount(42L).slug("old").nameZh("旧").position(1).active(false).icon("old-icon")
                .names(new java.util.HashMap<>(Map.of("es", "Vieja"))).createdAt(created).createdBy("creator").build();

        Category source = Category.builder().source("HACK").externalId("ext-evil").parentId(UUID.randomUUID())
                .productCount(999L).slug("new").nameZh("新").position(7).active(true).icon("new-icon")
                .names(Map.of("es", "Nueva")).build();

        mapper.updateFromModel(source, target);

        // Editables
        assertThat(target.getSlug()).isEqualTo("new");
        assertThat(target.getNameZh()).isEqualTo("新");
        assertThat(target.getPosition()).isEqualTo(7);
        assertThat(target.getActive()).isTrue();
        assertThat(target.getIcon()).isEqualTo("new-icon");
        assertThat(target.getNames()).containsEntry("es", "Nueva");

        // Preservados
        assertThat(target.getId()).isEqualTo(id);
        assertThat(target.getSource()).isEqualTo("PULL");
        assertThat(target.getExternalId()).isEqualTo("ext-1");
        assertThat(target.getParentId()).isEqualTo(parentId);
        assertThat(target.getProductCount()).isEqualTo(42L);
        assertThat(target.getCreatedAt()).isEqualTo(created);
        assertThat(target.getCreatedBy()).isEqualTo("creator");
    }

    @Test
    void updateFromModel_ignoresNullSourceFields() {
        Category target = Category.builder().slug("keep").icon("keep-icon").build();
        Category source = Category.builder().slug(null).icon("changed").build();

        mapper.updateFromModel(source, target);

        assertThat(target.getSlug()).isEqualTo("keep");
        assertThat(target.getIcon()).isEqualTo("changed");
    }
}
