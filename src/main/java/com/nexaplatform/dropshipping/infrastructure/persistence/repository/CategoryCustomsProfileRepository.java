package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryCustomsProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Acceso al perfil aduanero/de embalaje por categoría-hoja (o por familia, con el sufijo {@code -*}). */
public interface CategoryCustomsProfileRepository extends JpaRepository<CategoryCustomsProfileEntity, UUID> {

    Optional<CategoryCustomsProfileEntity> findByCategorySlug(String categorySlug);
}
