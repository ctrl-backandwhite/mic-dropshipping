package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryAttributeSchemaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** DROP-670: acceso al esquema de atributos por categoría. */
public interface CategoryAttributeSchemaRepository extends JpaRepository<CategoryAttributeSchemaEntity, UUID> {

    List<CategoryAttributeSchemaEntity> findByCategory_IdOrderByPositionAsc(UUID categoryId);

    Optional<CategoryAttributeSchemaEntity> findByCategory_IdAndAttrKey(UUID categoryId, String attrKey);
}
