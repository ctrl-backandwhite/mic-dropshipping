package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Acceso a los valores de un eje de variación (p.ej. los colores) para renombrar su etiqueta. */
public interface VariantValueRepository extends JpaRepository<VariantValueEntity, UUID> {

    /**
     * Valores de eje (color) con imagen de origen pendientes de espejar (sin cdn o con cdn de otro
     * storage) que NO han fallado ya antes. Los más nuevos primero.
     */
    @Query("SELECT v FROM VariantValueEntity v WHERE v.imageSourceUrl IS NOT NULL AND v.imageSourceUrl <> '' "
            + "AND v.imageMirrorFailedAt IS NULL "
            + "AND (v.imageCdnUrl IS NULL OR v.imageCdnUrl NOT LIKE :publicPrefix) "
            + "ORDER BY v.createdAt DESC NULLS LAST")
    List<VariantValueEntity> findNeedingImageMirror(@Param("publicPrefix") String publicPrefix, Pageable pageable);

    /** Fija la cdn_url espejada de la imagen del valor de eje. */
    @Modifying
    @Transactional
    @Query("UPDATE VariantValueEntity v SET v.imageCdnUrl = :cdn WHERE v.id = :id")
    void markImageCdn(@Param("id") UUID id, @Param("cdn") String cdn);

    /** Marca la imagen del valor como fallida (origen muerto) para no reintentarla en bucle. */
    @Modifying
    @Transactional
    @Query("UPDATE VariantValueEntity v SET v.imageMirrorFailedAt = :at WHERE v.id = :id")
    void markImageFailed(@Param("id") UUID id, @Param("at") Instant at);
}
