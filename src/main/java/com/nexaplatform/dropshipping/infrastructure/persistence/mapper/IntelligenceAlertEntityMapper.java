package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.IntelligenceAlert;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.IntelligenceAlertEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.List;

/**
 * Infrastructure-layer mapper between the {@link IntelligenceAlert} domain model
 * and the JPA entity. Builder disabled so MapStruct uses setters and can reach
 * the id/audit fields inherited from {@code BaseEntity}/{@code AuditableEntity}.
 * The {@code user} and {@code category} relations are resolved by the repository
 * adapter (which owns the managed entity), so they are ignored on {@code toEntity};
 * the domain side carries the flattened {@code userId}/{@code categoryId} plus the
 * read-only {@code categoryName} (the category's {@code nameZh}).
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface IntelligenceAlertEntityMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "userId", expression = "java(entity.getUser() != null ? entity.getUser().getId() : null)")
    @Mapping(target = "keyword", source = "keyword")
    @Mapping(target = "categoryId", expression = "java(entity.getCategory() != null ? entity.getCategory().getId() : null)")
    @Mapping(target = "categoryName", expression = "java(entity.getCategory() != null ? entity.getCategory().getNameZh() : null)")
    @Mapping(target = "channel", source = "channel")
    @Mapping(target = "thresholdScore", source = "thresholdScore")
    @Mapping(target = "active", source = "active")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "updatedBy", source = "updatedBy")
    IntelligenceAlert toDomain(IntelligenceAlertEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "keyword", source = "keyword")
    @Mapping(target = "category", ignore = true)
    @Mapping(target = "channel", source = "channel")
    @Mapping(target = "thresholdScore", source = "thresholdScore")
    @Mapping(target = "active", source = "active")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    IntelligenceAlertEntity toEntity(IntelligenceAlert model);

    /**
     * Aplica los campos escalares del modelo sobre una entidad gestionada (ruta de actualización).
     * Las relaciones gestionadas ({@code user}, {@code category}) y la auditoría las resuelve el
     * repositorio, por eso se ignoran aquí. MapStruct auto-mapea el resto por nombre.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "category", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    void updateEntity(@MappingTarget IntelligenceAlertEntity entity, IntelligenceAlert model);

    List<IntelligenceAlert> toDomainList(List<IntelligenceAlertEntity> entities);
}
