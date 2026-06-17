package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.Category;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryTranslationEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Infrastructure-layer mapper between the {@link Category} domain model and the
 * JPA entity. Builder disabled so MapStruct uses setters and can reach the
 * id/audit fields inherited from {@code BaseEntity}/{@code AuditableEntity}.
 * The self-referencing {@code parent} relation and the {@code translations}
 * collection are resolved by the repository adapter (which owns the managed
 * entity), so they are ignored on {@code toEntity}; the domain side carries the
 * flattened {@code parentId} and {@code names} map. {@code productCount} is a
 * read-only field filled by the use case and has no entity counterpart.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface CategoryEntityMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "slug", source = "slug")
    @Mapping(target = "source", source = "source")
    @Mapping(target = "externalId", source = "externalId")
    @Mapping(target = "nameZh", source = "nameZh")
    @Mapping(target = "position", source = "position")
    @Mapping(target = "active", source = "active")
    @Mapping(target = "icon", source = "icon")
    @Mapping(target = "parentId", expression = "java(entity.getParent() != null ? entity.getParent().getId() : null)")
    @Mapping(target = "names", source = "translations", qualifiedByName = "translationsToMap")
    @Mapping(target = "productCount", ignore = true)
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "updatedBy", source = "updatedBy")
    Category toDomain(CategoryEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "slug", source = "slug")
    @Mapping(target = "source", source = "source")
    @Mapping(target = "externalId", source = "externalId")
    @Mapping(target = "nameZh", source = "nameZh")
    @Mapping(target = "position", expression = "java(model.getPosition() != null ? model.getPosition() : 0)")
    @Mapping(target = "active", expression = "java(model.getActive() != null && model.getActive())")
    @Mapping(target = "icon", source = "icon")
    @Mapping(target = "parent", ignore = true)
    @Mapping(target = "translations", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    CategoryEntity toEntity(Category model);

    /**
     * Aplica los campos escalares del modelo sobre una entidad gestionada (ruta de actualización).
     * La relación auto-referente {@code parent} y la colección {@code translations} las resuelve el
     * repositorio (upsert in-place), por eso se ignoran aquí; la auditoría también. {@code position}
     * y {@code active} conservan el saneado null→0 / null→false del mapeo manual original. MapStruct
     * auto-mapea el resto por nombre.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "position", expression = "java(model.getPosition() != null ? model.getPosition() : 0)")
    @Mapping(target = "active", expression = "java(model.getActive() != null && model.getActive())")
    @Mapping(target = "parent", ignore = true)
    @Mapping(target = "translations", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    void updateEntity(@MappingTarget CategoryEntity entity, Category model);

    List<Category> toDomainList(List<CategoryEntity> entities);

    /** Collapses the translation list into a {language -> name} map. */
    @Named("translationsToMap")
    default Map<String, String> translationsToMap(List<CategoryTranslationEntity> translations) {
        Map<String, String> names = new HashMap<>();
        if (translations != null) {
            for (CategoryTranslationEntity tr : translations) {
                names.put(tr.getLanguage(), tr.getName());
            }
        }
        return names;
    }
}
