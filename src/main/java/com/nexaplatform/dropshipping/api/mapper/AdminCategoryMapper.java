package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.in.AdminCategoryUpsertDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminCategoryDtoOut;
import com.nexaplatform.dropshipping.domain.model.Category;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper for admin categories: translates between the transport DTOs and
 * the {@link Category} domain model. Injected in the controller. DtoOut field names
 * preserve the exact JSON keys the frontend already consumes (slug, nameZh, names,
 * icon, position, active, parentId, productCount).
 */
@Mapper(componentModel = "spring")
public interface AdminCategoryMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "slug", source = "slug")
    @Mapping(target = "nameZh", source = "nameZh")
    @Mapping(target = "names", source = "names")
    @Mapping(target = "icon", source = "icon")
    @Mapping(target = "position", expression = "java(model.getPosition() != null ? model.getPosition() : 0)")
    @Mapping(target = "active", expression = "java(model.getActive() != null && model.getActive())")
    @Mapping(target = "parentId", source = "parentId")
    @Mapping(target = "productCount", source = "productCount")
    AdminCategoryDtoOut toDtoOut(Category model);

    List<AdminCategoryDtoOut> toDtoOutList(List<Category> models);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "source", ignore = true)
    @Mapping(target = "externalId", ignore = true)
    @Mapping(target = "productCount", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "slug", source = "slug")
    @Mapping(target = "nameZh", source = "nameZh")
    @Mapping(target = "icon", source = "icon")
    @Mapping(target = "position", source = "position")
    @Mapping(target = "active", source = "active")
    @Mapping(target = "parentId", source = "parentId")
    @Mapping(target = "names", source = "names")
    Category toDomain(AdminCategoryUpsertDtoIn dtoIn);
}
