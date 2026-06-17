package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.AcademyEnrollment;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AcademyEnrollmentEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.List;

/**
 * Infrastructure-layer mapper between the {@link AcademyEnrollment} domain model
 * and the JPA entity. Builder disabled so MapStruct uses setters and can reach
 * the id/audit fields inherited from {@code BaseEntity}/{@code AuditableEntity}.
 * The {@code user} and {@code course} relations are resolved by the repository
 * adapter (which owns the managed entity), so they are ignored on
 * {@code toEntity}; the domain side carries the flattened {@code userId} and the
 * {@code courseId}/{@code courseSlug}/{@code courseTitle} read fields.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface AcademyEnrollmentEntityMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "userId", expression = "java(entity.getUser() != null ? entity.getUser().getId() : null)")
    @Mapping(target = "courseId", expression = "java(entity.getCourse() != null ? entity.getCourse().getId() : null)")
    @Mapping(target = "courseSlug", expression = "java(entity.getCourse() != null ? entity.getCourse().getSlug() : null)")
    @Mapping(target = "courseTitle", expression = "java(entity.getCourse() != null ? entity.getCourse().getTitle() : null)")
    @Mapping(target = "progressPct", source = "progressPct")
    @Mapping(target = "completedAt", source = "completedAt")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "updatedBy", source = "updatedBy")
    AcademyEnrollment toDomain(AcademyEnrollmentEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "course", ignore = true)
    @Mapping(target = "progressPct", source = "progressPct")
    @Mapping(target = "completedAt", source = "completedAt")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    AcademyEnrollmentEntity toEntity(AcademyEnrollment model);

    /**
     * Aplica los campos escalares del modelo sobre una entidad gestionada (ruta de actualización).
     * Las relaciones gestionadas ({@code user}, {@code course}) y la auditoría las resuelve el
     * repositorio, por eso se ignoran aquí. MapStruct auto-mapea el resto por nombre.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "course", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    void updateEntity(@MappingTarget AcademyEnrollmentEntity entity, AcademyEnrollment model);

    List<AcademyEnrollment> toDomainList(List<AcademyEnrollmentEntity> entities);
}
