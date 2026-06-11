package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.in.AddressDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AddressDtoOut;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserAddressEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.List;

/**
 * API-layer mapper: translates between the address transport DTOs and the JPA
 * entity. Every field is mapped explicitly. The {@code disableBuilder} option is
 * used because the entity's Lombok {@code @Builder} omits the inherited id/audit
 * fields, so MapStruct mutates a {@code new} instance via setters instead.
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
    AddressDtoOut toDtoOut(UserAddressEntity entity);

    List<AddressDtoOut> toDtoOutList(List<UserAddressEntity> entities);

    /**
     * Apply the editable fields of the input payload onto an entity. The
     * {@code user}, {@code isDefault} flag and audit/id fields are managed by the
     * service and intentionally ignored here.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "default", ignore = true)
    @Mapping(target = "label", source = "label")
    @Mapping(target = "fullName", source = "fullName")
    @Mapping(target = "phone", source = "phone")
    @Mapping(target = "line1", source = "line1")
    @Mapping(target = "line2", source = "line2")
    @Mapping(target = "city", source = "city")
    @Mapping(target = "state", source = "state")
    @Mapping(target = "postalCode", source = "postalCode")
    @Mapping(target = "country", source = "country")
    void applyToEntity(AddressDtoIn dtoIn, @MappingTarget UserAddressEntity entity);
}
