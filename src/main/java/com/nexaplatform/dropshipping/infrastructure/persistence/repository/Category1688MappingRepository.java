package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.Category1688MappingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** DROP-677: acceso al mapeo de categorías 1688 → categoría interna. */
public interface Category1688MappingRepository extends JpaRepository<Category1688MappingEntity, UUID> {

    Optional<Category1688MappingEntity> findByExternal1688Id(String external1688Id);

    Optional<Category1688MappingEntity> findFirstByExternal1688NameIgnoreCase(String external1688Name);
}
