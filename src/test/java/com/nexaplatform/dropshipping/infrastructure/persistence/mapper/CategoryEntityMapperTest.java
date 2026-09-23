package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.Category;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryTranslationEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mapper Model&lt;-&gt;Entity de {@link Category}. El mapper es autocontenido (sin {@code uses=...}),
 * por lo que {@code Mappers.getMapper} basta: no hay mappers anidados que inyectar. Cubre el
 * round-trip de escalares (toEntity descarta auditoría, relaciones y traducciones, que las resuelve
 * el repositorio) y la colapsación translations -&gt; names en toDomain.
 */
class CategoryEntityMapperTest {

    private final CategoryEntityMapper mapper = Mappers.getMapper(CategoryEntityMapper.class);

    @Test
    void roundTrip_preservesScalarsAndDropsAuditAndManagedRelations() {
        UUID id = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        Map<String, String> names = new HashMap<>();
        names.put("es", "Electrónica");
        names.put("en", "Electronics");

        Category model = Category.builder().id(id).slug("electronica").source("1688").externalId("EXT-9").nameZh("电子产品")
                .position(3).active(true).icon("bolt").parentId(parentId).names(names).productCount(42L)
                .createdAt(Instant.now()).updatedAt(Instant.now()).createdBy("seed").updatedBy("editor").build();

        CategoryEntity entity = mapper.toEntity(model);

        // toEntity IGNORA auditoría y relaciones gestionadas
        assertThat(entity.getCreatedAt()).isNull();
        assertThat(entity.getUpdatedAt()).isNull();
        assertThat(entity.getCreatedBy()).isNull();
        assertThat(entity.getUpdatedBy()).isNull();
        assertThat(entity.getParent()).isNull();
        assertThat(entity.getTranslations()).isEmpty();

        Category back = mapper.toDomain(entity);

        // Round-trip de escalares: ignoramos lo que el mapper no transporta por diseño
        // (auditoría, parentId/names/productCount sin contraparte resuelta en este camino).
        assertThat(back).usingRecursiveComparison()
                .ignoringFields("createdAt", "updatedAt", "createdBy", "updatedBy", "parentId", "names", "productCount")
                .isEqualTo(model);

        // Saneado null -&gt; 0 / false del toEntity sobre position/active
        Category empty = mapper.toEntity(Category.builder().slug("s").build()) == null
                ? null
                : mapper.toDomain(mapper.toEntity(Category.builder().slug("s").build()));
        assertThat(empty).isNotNull();
        assertThat(empty.getPosition()).isZero();
        assertThat(empty.getActive()).isFalse();
    }

    @Test
    void toDomain_collapsesTranslationsIntoNamesMapAndResolvesParentId() {
        UUID parentId = UUID.randomUUID();
        CategoryEntity parent = CategoryEntity.builder().slug("root").build();
        parent.setId(parentId);

        CategoryTranslationEntity esTr = CategoryTranslationEntity.builder().language("es").name("Hogar").build();
        CategoryTranslationEntity enTr = CategoryTranslationEntity.builder().language("en").name("Home").build();
        List<CategoryTranslationEntity> translations = new ArrayList<>(List.of(esTr, enTr));

        CategoryEntity entity = CategoryEntity.builder().slug("hogar").nameZh("家居").position(1).active(true)
                .parent(parent).translations(translations).build();
        entity.setId(UUID.randomUUID());

        Category model = mapper.toDomain(entity);

        assertThat(model.getParentId()).isEqualTo(parentId);
        assertThat(model.getNames()).hasSize(2).containsEntry("es", "Hogar").containsEntry("en", "Home");
    }
}
