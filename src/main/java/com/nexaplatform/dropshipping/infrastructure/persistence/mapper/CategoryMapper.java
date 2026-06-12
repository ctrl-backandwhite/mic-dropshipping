package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.CategoryTreeView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.CategoryView;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryTranslationEntity;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class CategoryMapper {

    public CategoryView toView(CategoryEntity entity) {
        if (entity == null)
            return null;
        Map<String, String> names = entity.getTranslations() == null
                ? Collections.emptyMap()
                : entity.getTranslations().stream().collect(Collectors.toMap(CategoryTranslationEntity::getLanguage,
                        CategoryTranslationEntity::getName, (a, b) -> a));
        return new CategoryView(entity.getId(), entity.getSlug(), entity.getNameZh(), names,
                entity.getParent() != null ? entity.getParent().getId() : null, entity.getPosition(), entity.getIcon(),
                entity.isActive());
    }

    public CategoryTreeView toTreeView(CategoryEntity entity, List<CategoryTreeView> children) {
        Map<String, String> names = entity.getTranslations() == null
                ? Collections.emptyMap()
                : entity.getTranslations().stream().collect(Collectors.toMap(CategoryTranslationEntity::getLanguage,
                        CategoryTranslationEntity::getName, (a, b) -> a));
        return new CategoryTreeView(entity.getId(), entity.getSlug(), entity.getNameZh(), names, entity.getIcon(),
                children);
    }
}
