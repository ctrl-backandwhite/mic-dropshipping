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

    /**
     * Marca una imagen como espejada: fija la cdn_url (S3/MinIO) + metadatos. Cada llamada, su propia tx.
     *
     * <p>Guarda también el ANCHO y el ALTO. Las columnas existían desde el principio y estaban vacías en
     * las 72.919 imágenes: nadie las escribía. Sin ellas la ficha no puede reservar el hueco de la foto,
     * así que el texto salta hacia abajo cada vez que una imagen termina de cargar —y eso, además de
     * incómodo, penaliza en las medidas de experiencia que usan los buscadores—.
     *
     * <p>Un cero significa «no se pudo averiguar» y se guarda como nulo, para no hacer creer que la
     * imagen mide cero píxeles.
     */
    @Modifying
    @Transactional
    @Query("UPDATE ProductImageEntity i SET i.cdnUrl = :cdnUrl, i.bytes = :bytes, i.hash = :hash, "
            + "i.width = :ancho, i.height = :alto, "
            + "i.mirrorStatus = :status, i.mirroredAt = :at WHERE i.id = :id")
    void markMirrored(@Param("id") UUID id, @Param("cdnUrl") String cdnUrl, @Param("bytes") Long bytes,
            @Param("hash") String hash, @Param("ancho") Integer ancho, @Param("alto") Integer alto,
            @Param("status") MirrorStatus status, @Param("at") Instant at);

    /** Cambia solo el estado de mirror (p.ej. a FAILED). */
    @Modifying
    @Transactional
    @Query("UPDATE ProductImageEntity i SET i.mirrorStatus = :status WHERE i.id = :id")
    void markStatus(@Param("id") UUID id, @Param("status") MirrorStatus status);

    /** Marca el fallo y suma un intento, que es lo que espacia el siguiente. */
    @Modifying
    @Transactional
    @Query("UPDATE ProductImageEntity i SET i.mirrorStatus = com.nexaplatform.dropshipping.domain.enums.MirrorStatus.FAILED, "
            + "i.mirrorAttempts = i.mirrorAttempts + 1 WHERE i.id = :id")
    void markFailedAndCountAttempt(@Param("id") UUID id);

    /**
     * Candidatas a reintento: las fallidas que aún no han agotado sus intentos, de la más antigua a la más
     * nueva.
     *
     * <p>Devuelve candidatas, no elegidas: cuál toca de verdad depende de la espera de cada una —base ×
     * 2^intentos— y eso se decide en el servicio, donde el reloj es inyectable y se puede probar sin base
     * de datos. Aquí solo se acota el conjunto para no traerse 4.800 filas a memoria.
     */
    @Query("SELECT i FROM ProductImageEntity i "
            + "WHERE i.mirrorStatus = com.nexaplatform.dropshipping.domain.enums.MirrorStatus.FAILED "
            + "AND i.mirrorAttempts < :maxAttempts ORDER BY i.updatedAt ASC LIMIT :limit")
    List<ProductImageEntity> findFailedForRetry(@Param("maxAttempts") int maxAttempts, @Param("limit") int limit);

    /** Una imagen espejada empieza de cero: si mañana hay que re-espejarla, no arrastra los fallos de ayer. */
    @Modifying
    @Transactional
    @Query("UPDATE ProductImageEntity i SET i.mirrorAttempts = 0 WHERE i.id = :id")
    void resetAttempts(@Param("id") UUID id);

    /** Devuelve a la cola las imágenes indicadas. El contador de intentos NO se toca: es lo que las espacia. */
    @Modifying
    @Transactional
    @Query("UPDATE ProductImageEntity i SET i.mirrorStatus = com.nexaplatform.dropshipping.domain.enums.MirrorStatus.PENDING "
            + "WHERE i.id IN :ids")
    int requeueToPending(@Param("ids") List<UUID> ids);

    /** Reencola para re-espejar: pone PENDING todo lo que no apunte aún a nuestro storage (backfill/retry). */
    @Modifying
    @Transactional
    @Query("UPDATE ProductImageEntity i SET i.mirrorStatus = com.nexaplatform.dropshipping.domain.enums.MirrorStatus.PENDING "
            + "WHERE i.mirrorStatus <> com.nexaplatform.dropshipping.domain.enums.MirrorStatus.MIRRORED "
            + "OR i.cdnUrl IS NULL OR i.cdnUrl NOT LIKE :publicPrefix")
    int requeueNotMirrored(@Param("publicPrefix") String publicPrefix);

    /**
     * Cuántas imágenes siguen guardadas SIN comprimir.
     *
     * <p>Se reconocen por no tener dimensiones: hasta el 4-sep-2026 nadie las escribía, así que una
     * imagen sin ancho es, exactamente, una que se guardó antes de que existiera el compresor. Sirve para
     * saber cuánto queda del histórico sin tener que adivinarlo.
     */
    long countByMirrorStatusAndWidthIsNull(MirrorStatus status);

    /**
     * Devuelve a la cola un LOTE de las imágenes guardadas sin comprimir, para que el barrido las
     * reprocese y queden aligeradas.
     *
     * <p>Por lotes y no todas de golpe a propósito: son casi setenta y tres mil imágenes y reprocesarlas
     * es volver a descargarlas del origen y recomprimirlas. Lanzado de una vez, eso satura la red de
     * salida, el almacén y la CPU del servidor a la vez, y lo hace mientras hay gente comprando.
     *
     * <p>Consulta nativa porque JPQL no admite un límite en un UPDATE, y aquí el límite es justamente el
     * punto: es lo que convierte una operación peligrosa en una que se puede ir dando poco a poco.
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE product_image SET mirror_status = 'PENDING' WHERE id IN ("
            + "SELECT id FROM product_image WHERE mirror_status = 'MIRRORED' AND width IS NULL "
            + "ORDER BY bytes DESC LIMIT :limite)", nativeQuery = true)
    int reencolarSinComprimir(@Param("limite") int limite);
}
