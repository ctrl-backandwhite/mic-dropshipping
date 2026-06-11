package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.AcademyCourse;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AcademyCourseEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Infrastructure-layer mapper between the {@link AcademyCourse} domain model and
 * the JPA entity. Builder disabled so MapStruct uses setters and can reach the
 * id/audit fields inherited from {@code BaseEntity}/{@code AuditableEntity}.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface AcademyCourseEntityMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "slug", source = "slug")
    @Mapping(target = "title", source = "title")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "instructor", source = "instructor")
    @Mapping(target = "durationMinutes", source = "durationMinutes")
    @Mapping(target = "coverUrl", source = "coverUrl")
    @Mapping(target = "videoUrl", source = "videoUrl")
    @Mapping(target = "locale", source = "locale")
    @Mapping(target = "level", source = "level")
    @Mapping(target = "published", source = "published")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "updatedBy", source = "updatedBy")
    AcademyCourse toDomain(AcademyCourseEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "slug", source = "slug")
    @Mapping(target = "title", source = "title")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "instructor", source = "instructor")
    @Mapping(target = "durationMinutes", source = "durationMinutes")
    @Mapping(target = "coverUrl", source = "coverUrl")
    @Mapping(target = "videoUrl", source = "videoUrl")
    @Mapping(target = "locale", source = "locale")
    @Mapping(target = "level", source = "level")
    @Mapping(target = "published", source = "published")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    AcademyCourseEntity toEntity(AcademyCourse model);

    List<AcademyCourse> toDomainList(List<AcademyCourseEntity> entities);
}
