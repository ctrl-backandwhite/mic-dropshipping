package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.CarbonFootprintDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.ShippingRateDtoOut;
import com.nexaplatform.dropshipping.domain.model.CarbonFootprint;
import com.nexaplatform.dropshipping.domain.model.ShippingRate;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper for the shipping calculator / ESG endpoints: translates the
 * {@link ShippingRate} / {@link CarbonFootprint} domain models into the transport
 * DTOs. Injected in the controller. DtoOut field names preserve the exact JSON
 * keys the frontend already consumes.
 */
@Mapper(componentModel = "spring")
public interface ShippingDtoMapper {

    @Mapping(target = "method", source = "method")
    @Mapping(target = "carrier", source = "carrier")
    @Mapping(target = "cost", source = "cost")
    @Mapping(target = "transitMin", source = "transitMin")
    @Mapping(target = "transitMax", source = "transitMax")
    ShippingRateDtoOut toRateDtoOut(ShippingRate model);

    List<ShippingRateDtoOut> toRateDtoOutList(List<ShippingRate> models);

    @Mapping(target = "carbonKg", source = "carbonKg")
    @Mapping(target = "offsetUsd", source = "offsetUsd")
    @Mapping(target = "greenestMethod", source = "greenestMethod")
    CarbonFootprintDtoOut toCarbonDtoOut(CarbonFootprint model);
}
