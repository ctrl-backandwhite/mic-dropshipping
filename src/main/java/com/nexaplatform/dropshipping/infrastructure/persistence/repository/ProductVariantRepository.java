package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductVariantRepository extends JpaRepository<ProductVariantEntity, UUID> {
    List<ProductVariantEntity> findByProductId(UUID productId);

    Optional<ProductVariantEntity> findByProductIdAndExternalId(UUID productId, String externalId);

    Optional<ProductVariantEntity> findBySku(String sku);

    /**
     * Variantes con imagen de origen pendientes de espejar (sin cdn o con cdn de otro storage) que NO
     * han fallado ya antes. Las más nuevas primero, para que un import reciente se espeje cuanto antes.
     */
    @Query("SELECT v FROM ProductVariantEntity v WHERE v.imageSourceUrl IS NOT NULL AND v.imageSourceUrl <> '' "
            + "AND v.imageMirrorFailedAt IS NULL "
            + "AND (v.imageCdnUrl IS NULL OR v.imageCdnUrl NOT LIKE :publicPrefix) "
            + "ORDER BY v.createdAt DESC NULLS LAST")
    List<ProductVariantEntity> findNeedingImageMirror(@Param("publicPrefix") String publicPrefix, Pageable pageable);

    /** Fija la cdn_url espejada de la imagen de la variante. */
    @Modifying
    @Transactional
    @Query("UPDATE ProductVariantEntity v SET v.imageCdnUrl = :cdn WHERE v.id = :id")
    void markImageCdn(@Param("id") UUID id, @Param("cdn") String cdn);

    /** Marca la imagen de la variante como fallida (origen muerto) para no reintentarla en bucle. */
    @Modifying
    @Transactional
    @Query("UPDATE ProductVariantEntity v SET v.imageMirrorFailedAt = :at WHERE v.id = :id")
    void markImageFailed(@Param("id") UUID id, @Param("at") java.time.Instant at);
}
