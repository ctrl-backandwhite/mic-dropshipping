package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomsDeclarationGroupEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Los grupos de declaración, buscados por la terna que los identifica. */
public interface CustomsDeclarationGroupRepository extends JpaRepository<CustomsDeclarationGroupEntity, UUID> {

    /**
     * El grupo de una terna concreta. Los tres argumentos llegan ya normalizados (mayúsculas, sin
     * espacios dobles): la tabla los guarda así para que «Cotton» y «cotton » sean el mismo grupo.
     */
    Optional<CustomsDeclarationGroupEntity> findByHs6AndMaterialAndUsageCode(String hs6, String material,
            String usageCode);

    /**
     * Todos los grupos, los que más productos abarcan primero.
     *
     * <p>El orden no es estético: aprobar 185 descripciones de golpe no es realista, y empezar por las
     * partidas grandes cubre la mayor parte del catálogo con las primeras.
     */
    List<CustomsDeclarationGroupEntity> findAllByOrderByProductCountDesc();
}
