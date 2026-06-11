package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.CourseDtoOut;
import com.nexaplatform.dropshipping.domain.model.AcademyCourse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper for academy courses: translates the {@link AcademyCourse}
 * domain model into the transport DTO. Injected in the controller. DtoOut field
 * names preserve the exact JSON keys the frontend already consumes (mirroring the
 * legacy {@code CourseView} record). Read-only endpoints, so no {@code toDomain}.
 */
@Mapper(componentModel = "spring")
public interface AcademyCourseDtoMapper {

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
    @Mapping(target = "createdAt", source = "createdAt")
    CourseDtoOut toDtoOut(AcademyCourse model);

    List<CourseDtoOut> toDtoOutList(List<AcademyCourse> models);
}
