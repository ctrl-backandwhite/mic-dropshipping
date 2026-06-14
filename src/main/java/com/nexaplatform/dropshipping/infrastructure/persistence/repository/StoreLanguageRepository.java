package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.StoreLanguageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Acceso al registro de idiomas de la tienda. */
public interface StoreLanguageRepository extends JpaRepository<StoreLanguageEntity, UUID> {

    List<StoreLanguageEntity> findByActiveTrueOrderByPositionAsc();

    List<StoreLanguageEntity> findAllByOrderByPositionAsc();

    Optional<StoreLanguageEntity> findByCodeIgnoreCase(String code);
}
