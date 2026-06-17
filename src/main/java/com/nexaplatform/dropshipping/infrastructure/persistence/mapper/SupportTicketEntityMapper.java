package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.SupportTicket;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupportTicketEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.List;

/**
 * Infrastructure-layer mapper between the {@link SupportTicket} domain model and
 * the JPA entity. Builder disabled so MapStruct uses setters and can reach the
 * id/audit fields inherited from {@code BaseEntity}/{@code AuditableEntity}.
 * The {@code user} and {@code order} relations are resolved by the repository
 * adapter (which owns the managed entity), so they are ignored on
 * {@code toEntity}; the domain side carries the flattened {@code userId} /
 * {@code orderId}.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface SupportTicketEntityMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "userId", source = "user.id")
    @Mapping(target = "kind", source = "kind")
    @Mapping(target = "subject", source = "subject")
    @Mapping(target = "body", source = "body")
    @Mapping(target = "orderId", source = "order.id")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "priority", source = "priority")
    @Mapping(target = "resolution", source = "resolution")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "updatedBy", source = "updatedBy")
    SupportTicket toDomain(SupportTicketEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "kind", source = "kind")
    @Mapping(target = "subject", source = "subject")
    @Mapping(target = "body", source = "body")
    @Mapping(target = "order", ignore = true)
    @Mapping(target = "status", source = "status")
    @Mapping(target = "priority", source = "priority")
    @Mapping(target = "assignedTo", ignore = true)
    @Mapping(target = "resolution", source = "resolution")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    SupportTicketEntity toEntity(SupportTicket model);

    /**
     * Aplica los campos escalares del modelo sobre una entidad gestionada (ruta de actualización). El
     * {@code user}, {@code order} y {@code assignedTo} (relaciones gestionadas), la auditoría y el
     * {@code status} / {@code priority} (set condicional preservando el valor previo cuando es null) los
     * resuelve el repositorio, por eso se ignoran aquí. MapStruct auto-mapea el resto por nombre.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "order", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "priority", ignore = true)
    @Mapping(target = "assignedTo", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    void updateEntity(@MappingTarget SupportTicketEntity entity, SupportTicket model);

    List<SupportTicket> toDomainList(List<SupportTicketEntity> entities);
}
