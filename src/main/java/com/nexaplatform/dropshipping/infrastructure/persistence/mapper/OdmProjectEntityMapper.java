package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.OdmProject;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OdmProjectEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.List;

/**
 * Infrastructure-layer mapper between the {@link OdmProject} domain model and the
 * JPA entity. Builder disabled so MapStruct uses setters and can reach the
 * id/audit fields inherited from {@code BaseEntity}/{@code AuditableEntity}.
 * The {@code user} relation is resolved by the repository adapter (which owns the
 * managed entity), so it is ignored on {@code toEntity}; the domain side carries
 * the flattened {@code userId}.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface OdmProjectEntityMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "userId", source = "user.id")
    @Mapping(target = "kind", source = "kind")
    @Mapping(target = "title", source = "title")
    @Mapping(target = "brief", source = "brief")
    @Mapping(target = "budgetUsdCents", source = "budgetUsdCents")
    @Mapping(target = "slaDays", source = "slaDays")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "updatedBy", source = "updatedBy")
    OdmProject toDomain(OdmProjectEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "kind", source = "kind")
    @Mapping(target = "title", source = "title")
    @Mapping(target = "brief", source = "brief")
    @Mapping(target = "budgetUsdCents", source = "budgetUsdCents")
    @Mapping(target = "slaDays", source = "slaDays")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "assignedTo", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    OdmProjectEntity toEntity(OdmProject model);

    /**
     * Aplica los campos escalares del modelo sobre una entidad gestionada (ruta de actualización). El
     * {@code user} y {@code assignedTo} (relaciones gestionadas), la auditoría y el {@code status}
     * (set condicional preservando el valor previo cuando es null) los resuelve el repositorio, por eso
     * se ignoran aquí. MapStruct auto-mapea el resto por nombre.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "assignedTo", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    void updateEntity(@MappingTarget OdmProjectEntity entity, OdmProject model);

    List<OdmProject> toDomainList(List<OdmProjectEntity> entities);
}
