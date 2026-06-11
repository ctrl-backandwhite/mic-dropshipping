package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.AdminCategoryDtoOut;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryTranslationEntity;
import org.mapstruct.Mapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MapStruct mapper for the Admin Categories API boundary.
 * Builds the DtoOut from the JPA entity plus an externally computed product count.
 * Combined with Lombok: entity getters and DtoOut builders are Lombok-generated.
 */
@Mapper(componentModel = "spring")
public interface AdminCategoryMapper {

    /**
     * Builds the admin category view. The product count is computed outside the
     * mapper (aggregate query) and supplied as an argument.
     */
    default AdminCategoryDtoOut toView(CategoryEntity c, long productCount) {
        return AdminCategoryDtoOut.builder()
                .id(c.getId())
                .slug(c.getSlug())
                .nameZh(c.getNameZh())
                .names(translationsToMap(c.getTranslations()))
                .icon(c.getIcon())
                .position(c.getPosition())
                .active(c.isActive())
                .parentId(c.getParent() != null ? c.getParent().getId() : null)
                .productCount(productCount)
                .build();
    }

    /** Collapses the translation list into a {language -> name} map. */
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
