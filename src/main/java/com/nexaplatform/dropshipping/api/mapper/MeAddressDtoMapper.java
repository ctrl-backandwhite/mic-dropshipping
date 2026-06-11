package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.in.AddressDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AddressDtoOut;
import com.nexaplatform.dropshipping.domain.model.UserAddress;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper for the authenticated user's addresses: translates between the
 * transport DTOs and the {@link UserAddress} domain model. Injected in the
 * controller. The default flag keeps the legacy JSON key {@code default} (frontend
 * contract); ownership and audit are managed by the use case and ignored on the
 * way in.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface MeAddressDtoMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "label", source = "label")
    @Mapping(target = "fullName", source = "fullName")
    @Mapping(target = "phone", source = "phone")
    @Mapping(target = "line1", source = "line1")
    @Mapping(target = "line2", source = "line2")
    @Mapping(target = "city", source = "city")
    @Mapping(target = "state", source = "state")
    @Mapping(target = "postalCode", source = "postalCode")
    @Mapping(target = "country", source = "country")
    @Mapping(target = "default", source = "default")
    @Mapping(target = "createdAt", source = "createdAt")
    AddressDtoOut toDtoOut(UserAddress model);

    List<AddressDtoOut> toDtoOutList(List<UserAddress> models);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "label", source = "label")
    @Mapping(target = "fullName", source = "fullName")
    @Mapping(target = "phone", source = "phone")
    @Mapping(target = "line1", source = "line1")
    @Mapping(target = "line2", source = "line2")
    @Mapping(target = "city", source = "city")
    @Mapping(target = "state", source = "state")
    @Mapping(target = "postalCode", source = "postalCode")
    @Mapping(target = "country", source = "country")
    @Mapping(target = "default", expression = "java(Boolean.TRUE.equals(dtoIn.getIsDefault()))")
    UserAddress toDomain(AddressDtoIn dtoIn);
}
