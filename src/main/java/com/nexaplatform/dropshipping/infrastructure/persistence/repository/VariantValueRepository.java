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

    /**
     * Lo mismo, acotado a unos productos recién importados. El valor de eje cuelga del eje, y el eje del
     * producto, de ahí el doble salto.
     *
     * <p>Estas son las MUESTRAS DE COLOR: los botones que el comprador pulsa para elegir. Sin espejar se
     * ven rotas, porque el proveedor responde 403 a quien enlaza sus imágenes desde otra web.
     */
    @Query("SELECT v FROM VariantValueEntity v WHERE v.option.product.id IN :productIds "
            + "AND v.imageSourceUrl IS NOT NULL AND v.imageSourceUrl <> '' "
            + "AND v.imageMirrorFailedAt IS NULL "
            + "AND (v.imageCdnUrl IS NULL OR v.imageCdnUrl NOT LIKE :publicPrefix)")
    List<VariantValueEntity> findNeedingImageMirrorByProducts(@Param("publicPrefix") String publicPrefix,
            @Param("productIds") List<UUID> productIds);

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
    @Query(value = "UPDATE variant_value SET image_mirror_failed_at = NULL WHERE id IN ("
            + "SELECT id FROM variant_value WHERE image_mirror_failed_at IS NOT NULL "
            + "AND image_mirror_failed_at <= :antesDe ORDER BY image_mirror_failed_at LIMIT :tope)",
            nativeQuery = true)
    int requeueFailed(@Param("antesDe") Instant antesDe, @Param("tope") int tope);
}
