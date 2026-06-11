package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.in.PartnerApiKeyCreateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.PartnerApiKeyCreatedDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.PartnerApiKeyDtoOut;
import com.nexaplatform.dropshipping.domain.model.ApiKey;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper for self-service partner API keys: translates between the
 * transport DTOs and the {@link ApiKey} domain projection. Injected in the
 * controller. DtoOut field names preserve the exact JSON keys the frontend
 * already consumes (clientId, clientSecret, name, scopes, createdAt, plan,
 * message).
 */
@Mapper(componentModel = "spring")
public interface PartnerApiKeyDtoMapper {

    /** Request body -> domain create command (only name + scopes are carried). */
    @Mapping(target = "name", source = "name")
    @Mapping(target = "scopes", source = "scopes")
    @Mapping(target = "clientId", ignore = true)
    @Mapping(target = "clientSecret", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "plan", ignore = true)
    @Mapping(target = "message", ignore = true)
    ApiKey toDomain(PartnerApiKeyCreateDtoIn dtoIn);

    /** One-time creation projection -> creation response (carries the secret once). */
    @Mapping(target = "clientId", source = "clientId")
    @Mapping(target = "clientSecret", source = "clientSecret")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "scopes", source = "scopes")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "message", source = "message")
    PartnerApiKeyCreatedDtoOut toCreatedDtoOut(ApiKey model);

    /** Listed projection -> list response (no secret). */
    @Mapping(target = "clientId", source = "clientId")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "scopes", source = "scopes")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "plan", source = "plan")
    PartnerApiKeyDtoOut toDtoOut(ApiKey model);

    List<PartnerApiKeyDtoOut> toDtoOutList(List<ApiKey> models);
}
