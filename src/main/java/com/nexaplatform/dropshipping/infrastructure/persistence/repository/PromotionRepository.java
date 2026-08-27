package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PromotionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Acceso a las promociones. */
public interface PromotionRepository extends JpaRepository<PromotionEntity, UUID> {

    /**
     * Promociones vivas en un instante dado.
     *
     * <p>Se filtra en SQL y no en memoria porque el escaparate lo pregunta en cada listado: traer todo
     * el histórico de rebajas para descartarlo después crecería con cada temporada que pase.
     */
    @Query("""
            select p from PromotionEntity p
             where p.active = true
               and (p.startsAt is null or p.startsAt <= :now)
               and (p.endsAt is null or p.endsAt > :now)
               and (p.maxUses is null or p.usedCount < p.maxUses)
            """)
    List<PromotionEntity> findLive(@Param("now") Instant now);

    /** Resuelve un cupón por su código, sin distinguir mayúsculas. */
    @Query("select p from PromotionEntity p where upper(p.code) = upper(:code)")
    Optional<PromotionEntity> findByCodeIgnoreCase(@Param("code") String code);

    List<PromotionEntity> findAllByOrderByCreatedAtDesc();
}
