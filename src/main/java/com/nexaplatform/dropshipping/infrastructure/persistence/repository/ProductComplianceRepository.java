package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * Consultas de cumplimiento sobre el catálogo: cuántas referencias no publican la identidad del fabricante
 * que exige el art. 19.a del Reglamento (UE) 2023/988 y cuáles son.
 *
 * <p>Va en un repositorio propio y no en {@code ProductRepository} porque este solo lee, y ese archivo es
 * el que usa el escaparate: mezclar aquí consultas de panel obligaría a cargarlas en cada petición de
 * catálogo.
 */
public interface ProductComplianceRepository extends Repository<ProductEntity, UUID> {

    /**
     * Referencias ACTIVAS a las que les falta algún dato del fabricante.
     *
     * <p>Solo cuenta las activas porque la obligación del art. 19 recae sobre la oferta: un borrador o un
     * producto archivado no está ofertado al consumidor, y meterlos en el contador daría una cifra alarmante
     * que no se corresponde con el riesgo real.
     */
    @Query("""
            select count(p) from ProductEntity p
            where p.status = com.nexaplatform.dropshipping.domain.enums.ProductStatus.ACTIVE
              and (p.manufacturerName is null or trim(p.manufacturerName) = ''
                or p.manufacturerAddress is null or trim(p.manufacturerAddress) = ''
                or p.manufacturerEmail is null or trim(p.manufacturerEmail) = '')
            """)
    long contarActivasSinFabricante();

    /** Total de referencias activas, para poder dar el incumplimiento como proporción y no como número suelto. */
    @Query("""
            select count(p) from ProductEntity p
            where p.status = com.nexaplatform.dropshipping.domain.enums.ProductStatus.ACTIVE
            """)
    long contarActivas();

    /**
     * Las referencias activas incompletas, paginadas, para que el panel pueda listarlas y corregirlas.
     *
     * <p>Devuelve una PROYECCIÓN y no la entidad: el título vive en {@code product_translation}, que es una
     * colección perezosa, y resolverlo fuera de la sesión lanzaba {@code LazyInitializationException} →
     * 500 al abrir el listado. Resolviéndolo en el propio SQL desaparece el problema y de paso se evita
     * una consulta por fila.
     */
    @Query("""
            select p.id as id, p.slug as slug,
                   coalesce(t.title, p.titleZh) as title,
                   p.manufacturerName as manufacturerName,
                   p.manufacturerAddress as manufacturerAddress,
                   p.manufacturerEmail as manufacturerEmail
            from ProductEntity p
            left join p.translations t on lower(t.language) = lower(:lang)
            where p.status = com.nexaplatform.dropshipping.domain.enums.ProductStatus.ACTIVE
              and (p.manufacturerName is null or trim(p.manufacturerName) = ''
                or p.manufacturerAddress is null or trim(p.manufacturerAddress) = ''
                or p.manufacturerEmail is null or trim(p.manufacturerEmail) = '')
            order by p.createdAt desc
            """)
    Page<ProductoSinFabricante> buscarActivasSinFabricante(@Param("lang") String lang, Pageable pageable);

    /** Proyección del listado: lo justo para pintar la fila y saltar a la ficha. */
    interface ProductoSinFabricante {
        UUID getId();

        String getSlug();

        String getTitle();

        String getManufacturerName();

        String getManufacturerAddress();

        String getManufacturerEmail();
    }
}
