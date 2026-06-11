package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.in.PodDesignCreateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.PodAiGenerateDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.PodBlankProductDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.PodDesignDtoOut;
import com.nexaplatform.dropshipping.domain.model.PodAiResult;
import com.nexaplatform.dropshipping.domain.model.PodBlankProduct;
import com.nexaplatform.dropshipping.domain.model.PodDesign;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper for the POD aggregate: translates between the transport DTOs
 * and the {@link PodDesign} / {@link PodBlankProduct} / {@link PodAiResult} domain
 * models. Injected in the controller. DtoOut field names preserve the exact JSON
 * keys the frontend already consumes.
 */
@Mapper(componentModel = "spring")
public interface PodDesignDtoMapper {

    /* ---------- design ---------- */

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "productId", source = "productId")
    @Mapping(target = "productTitle", ignore = true)
    @Mapping(target = "name", source = "name")
    @Mapping(target = "canvasJson", source = "canvasJson")
    @Mapping(target = "mockupUrl", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "aiPrompt", source = "aiPrompt")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    PodDesign toDomain(PodDesignCreateDtoIn dtoIn);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "productId", source = "productId")
    @Mapping(target = "productTitle", source = "productTitle")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "canvasJson", source = "canvasJson")
    @Mapping(target = "mockupUrl", source = "mockupUrl")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "aiPrompt", source = "aiPrompt")
    @Mapping(target = "createdAt", source = "createdAt")
    PodDesignDtoOut toDtoOut(PodDesign model);

    List<PodDesignDtoOut> toDtoOutList(List<PodDesign> models);

    /* ---------- blank products ---------- */

    @Mapping(target = "id", source = "id")
    @Mapping(target = "slug", source = "slug")
    @Mapping(target = "title", source = "title")
    @Mapping(target = "mainImage", source = "mainImage")
    @Mapping(target = "price", source = "price")
    PodBlankProductDtoOut toBlankDtoOut(PodBlankProduct model);

    List<PodBlankProductDtoOut> toBlankDtoOutList(List<PodBlankProduct> models);

    /* ---------- ai generation ---------- */

    @Mapping(target = "mockupUrl", source = "mockupUrl")
    @Mapping(target = "prompt", source = "prompt")
    @Mapping(target = "provider", source = "provider")
    PodAiGenerateDtoOut toAiDtoOut(PodAiResult model);
}
