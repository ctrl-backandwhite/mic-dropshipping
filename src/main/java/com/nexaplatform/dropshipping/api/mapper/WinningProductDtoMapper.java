package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.WinningProductDtoOut;
import com.nexaplatform.dropshipping.domain.model.WinningProduct;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper for winning products: translates the read-only
 * {@link WinningProduct} domain model into the transport DTO. Injected in the
 * controller. DtoOut field names mirror the legacy {@code WinningProduct} record
 * (slug, title, monthlySales, trendScore, mainImage, price).
 */
@Mapper(componentModel = "spring")
public interface WinningProductDtoMapper {

    @Mapping(target = "slug", source = "slug")
    @Mapping(target = "title", source = "title")
    @Mapping(target = "monthlySales", source = "monthlySales")
    @Mapping(target = "trendScore", source = "trendScore")
    @Mapping(target = "mainImage", source = "mainImage")
    @Mapping(target = "price", source = "price")
    WinningProductDtoOut toDtoOut(WinningProduct model);

    List<WinningProductDtoOut> toDtoOutList(List<WinningProduct> models);
}
