package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.EnrollmentDtoOut;
import com.nexaplatform.dropshipping.domain.model.AcademyEnrollment;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper for academy enrollments: translates the {@link AcademyEnrollment}
 * domain model into the transport DTO. Injected in the controller. DtoOut field
 * names preserve the exact JSON keys the frontend already consumes (mirroring the
 * legacy {@code EnrollmentView} record).
 */
@Mapper(componentModel = "spring")
public interface AcademyEnrollmentDtoMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "courseId", source = "courseId")
    @Mapping(target = "courseSlug", source = "courseSlug")
    @Mapping(target = "courseTitle", source = "courseTitle")
    @Mapping(target = "progressPct", source = "progressPct")
    @Mapping(target = "completedAt", source = "completedAt")
    EnrollmentDtoOut toDtoOut(AcademyEnrollment model);

    List<EnrollmentDtoOut> toDtoOutList(List<AcademyEnrollment> models);
}
