package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
    void markImageFailed(@Param("id") UUID id, @Param("at") Instant at);

    /**
     * Descuento ATÓMICO de stock al concretarse la venta (pago confirmado). La condición
     * {@code stock >= qty} es el control de sobreventa: si no hay suficiente devuelve 0 filas
     * (no descuenta) y el llamador decide (nunca deja stock negativo). Se une a la transacción
     * del cambio de estado de la orden, por lo que descontar y marcar PAID son atómicos.
     */
    @Modifying
    @Query("UPDATE ProductVariantEntity v SET v.stock = v.stock - :qty WHERE v.id = :id AND v.stock >= :qty")
    int deductStock(@Param("id") UUID id, @Param("qty") int qty);

    /** Reintegra stock al cancelar/reembolsar (la venta no se concretó). */
    @Modifying
    @Query("UPDATE ProductVariantEntity v SET v.stock = v.stock + :qty WHERE v.id = :id")
    int restoreStock(@Param("id") UUID id, @Param("qty") int qty);

    /** Fija el stock a 0 (salvaguarda anti-sobreventa cuando el dinero ya se capturó y no se puede rechazar). */
    @Modifying
    @Query("UPDATE ProductVariantEntity v SET v.stock = 0 WHERE v.id = :id")
    int zeroStock(@Param("id") UUID id);

    /**
     * Devuelve a la cola las que fallaron hace ya un rato, limpiando la marca.
     *
     * <p>Sin esto la marca era DEFINITIVA: el barrido descarta lo que la tiene, el propio barrido la
     * pone al fallar y nadie la quitaba nunca. El 18-sep-2026, tras cargar 9.425 productos, el 96% de
     * las muestras de color se quedó sin espejar y apuntando al proveedor —que rechaza el enlazado con
     * un 403—, sin registro, sin forma de recuperarlas desde el panel y sin moverse en tres medidas.
     *
     * <p>Se limita por fecha y por número para no repetir la avalancha que hizo fallar el espejado en
     * primer lugar: lo que acaba de fallar espera su turno.
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE product_variant SET image_mirror_failed_at = NULL WHERE id IN ("
            + "SELECT id FROM product_variant WHERE image_mirror_failed_at IS NOT NULL "
            + "AND image_mirror_failed_at <= :antesDe ORDER BY image_mirror_failed_at LIMIT :tope)",
            nativeQuery = true)
    int requeueFailed(@Param("antesDe") Instant antesDe, @Param("tope") int tope);
}
