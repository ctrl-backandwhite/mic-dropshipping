package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.domain.enums.MirrorStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ProductImageRepository extends JpaRepository<ProductImageEntity, UUID> {
    List<ProductImageEntity> findByProductIdOrderByPositionAsc(UUID productId);

    /** Newest-first: lo recién importado se espeja primero → aparece antes en el escaparate durante la carga. */
    List<ProductImageEntity> findTop100ByMirrorStatusOrderByCreatedAtDesc(MirrorStatus status);

    long countByMirrorStatus(MirrorStatus status);

    /** IDs de producto de un conjunto de imágenes — para reindexar en OpenSearch tras espejarlas. */
    @Query("SELECT DISTINCT i.product.id FROM ProductImageEntity i WHERE i.id IN :imageIds")
    List<UUID> findProductIdsByImageIds(@Param("imageIds") List<UUID> imageIds);

    /** Imágenes en un estado de un conjunto de productos — para espejar YA lo recién importado. */
    List<ProductImageEntity> findByProductIdInAndMirrorStatus(List<UUID> productIds, MirrorStatus status);

    /** Imágenes MIRRORED cuyo cdn_url empieza por el prefijo dado (nuestro storage) — para verificar objetos. */
    List<ProductImageEntity> findByMirrorStatusAndCdnUrlStartingWith(MirrorStatus status, String cdnUrlPrefix);

    /** Marca una imagen como espejada: fija la cdn_url (S3/MinIO) + metadatos. Cada llamada, su propia tx. */
    @Modifying
    @Transactional
    @Query("UPDATE ProductImageEntity i SET i.cdnUrl = :cdnUrl, i.bytes = :bytes, i.hash = :hash, "
            + "i.mirrorStatus = :status, i.mirroredAt = :at WHERE i.id = :id")
    void markMirrored(@Param("id") UUID id, @Param("cdnUrl") String cdnUrl, @Param("bytes") Long bytes,
            @Param("hash") String hash, @Param("status") MirrorStatus status, @Param("at") Instant at);

    /** Cambia solo el estado de mirror (p.ej. a FAILED). */
    @Modifying
    @Transactional
    @Query("UPDATE ProductImageEntity i SET i.mirrorStatus = :status WHERE i.id = :id")
    void markStatus(@Param("id") UUID id, @Param("status") MirrorStatus status);

    /** Reencola para re-espejar: pone PENDING todo lo que no apunte aún a nuestro storage (backfill/retry). */
    @Modifying
    @Transactional
    @Query("UPDATE ProductImageEntity i SET i.mirrorStatus = com.nexaplatform.dropshipping.domain.enums.MirrorStatus.PENDING "
            + "WHERE i.mirrorStatus <> com.nexaplatform.dropshipping.domain.enums.MirrorStatus.MIRRORED "
            + "OR i.cdnUrl IS NULL OR i.cdnUrl NOT LIKE :publicPrefix")
    int requeueNotMirrored(@Param("publicPrefix") String publicPrefix);
}
