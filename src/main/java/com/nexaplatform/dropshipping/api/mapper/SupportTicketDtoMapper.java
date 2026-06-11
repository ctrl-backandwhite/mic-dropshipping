package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.in.SupportTicketCreateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.SupportTicketDtoOut;
import com.nexaplatform.dropshipping.domain.model.SupportTicket;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper for the support-ticket aggregate: translates between the
 * transport DTOs and the {@link SupportTicket} domain model. Injected in the
 * controller. DtoOut field names preserve the exact JSON keys the frontend
 * already consumes.
 */
@Mapper(componentModel = "spring")
public interface SupportTicketDtoMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "kind", source = "kind")
    @Mapping(target = "subject", source = "subject")
    @Mapping(target = "body", source = "body")
    @Mapping(target = "orderId", source = "orderId")
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "priority", source = "priority")
    @Mapping(target = "resolution", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    SupportTicket toDomain(SupportTicketCreateDtoIn dtoIn);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "kind", source = "kind")
    @Mapping(target = "subject", source = "subject")
    @Mapping(target = "body", source = "body")
    @Mapping(target = "orderId", source = "orderId")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "priority", source = "priority")
    @Mapping(target = "resolution", source = "resolution")
    @Mapping(target = "createdAt", source = "createdAt")
    SupportTicketDtoOut toDtoOut(SupportTicket model);

    List<SupportTicketDtoOut> toDtoOutList(List<SupportTicket> models);
}
