package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.in.OdmProjectCreateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.OdmProjectDtoOut;
import com.nexaplatform.dropshipping.domain.model.OdmProject;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper for the ODM/OEM aggregate: translates between the transport
 * DTOs and the {@link OdmProject} domain model. Injected in the controller.
 * DtoOut field names preserve the exact JSON keys the frontend already consumes.
 */
@Mapper(componentModel = "spring")
public interface OdmProjectDtoMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "kind", source = "kind")
    @Mapping(target = "title", source = "title")
    @Mapping(target = "brief", source = "brief")
    @Mapping(target = "budgetUsdCents", source = "budgetUsdCents")
    @Mapping(target = "slaDays", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    OdmProject toDomain(OdmProjectCreateDtoIn dtoIn);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "kind", source = "kind")
    @Mapping(target = "title", source = "title")
    @Mapping(target = "brief", source = "brief")
    @Mapping(target = "budgetUsdCents", source = "budgetUsdCents")
    @Mapping(target = "slaDays", source = "slaDays")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "createdAt", source = "createdAt")
    OdmProjectDtoOut toDtoOut(OdmProject model);

    List<OdmProjectDtoOut> toDtoOutList(List<OdmProject> models);
}
