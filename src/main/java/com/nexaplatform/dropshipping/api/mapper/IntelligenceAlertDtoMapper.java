package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.in.AlertRequestDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AlertViewDtoOut;
import com.nexaplatform.dropshipping.domain.model.IntelligenceAlert;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper for intelligence alerts: translates between the transport DTOs
 * and the {@link IntelligenceAlert} domain model. Injected in the controller.
 * DtoOut field names mirror the legacy {@code AlertView} record (id, keyword,
 * categoryId, categoryName, channel, thresholdScore, active, createdAt). The
 * owner/audit fields are stamped by the use case, not the request payload.
 */
@Mapper(componentModel = "spring")
public interface IntelligenceAlertDtoMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "keyword", source = "keyword")
    @Mapping(target = "categoryId", source = "categoryId")
    @Mapping(target = "categoryName", source = "categoryName")
    @Mapping(target = "channel", source = "channel")
    @Mapping(target = "thresholdScore", source = "thresholdScore")
    @Mapping(target = "active", source = "active")
    @Mapping(target = "createdAt", source = "createdAt")
    AlertViewDtoOut toDtoOut(IntelligenceAlert model);

    List<AlertViewDtoOut> toDtoOutList(List<IntelligenceAlert> models);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "keyword", source = "keyword")
    @Mapping(target = "categoryId", source = "categoryId")
    @Mapping(target = "categoryName", ignore = true)
    @Mapping(target = "channel", source = "channel")
    @Mapping(target = "thresholdScore", source = "thresholdScore")
    @Mapping(target = "active", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    IntelligenceAlert toDomain(AlertRequestDtoIn dtoIn);
}
