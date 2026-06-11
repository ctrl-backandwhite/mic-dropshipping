package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.ShopDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.ShopListingDtoOut;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ShopConnectionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ShopProductListingEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for the My Shops API boundary. Translates the JPA entities
 * into transport DtoOut classes, preserving the exact JSON field names of the
 * previous {@code ShopView}/{@code ListingView} records. The listings count is
 * computed outside the mapper (aggregate query) and supplied as an argument.
 */
@Mapper(componentModel = "spring")
public interface ShopDtoMapper {

    @Mapping(target = "id", source = "shop.id")
    @Mapping(target = "platform", source = "shop.platform")
    @Mapping(target = "shopHandle", source = "shop.shopHandle")
    @Mapping(target = "status", source = "shop.status")
    @Mapping(target = "lastSyncAt", source = "shop.lastSyncAt")
    @Mapping(target = "createdAt", source = "shop.createdAt")
    @Mapping(target = "listings", source = "listings")
    ShopDtoOut toShop(ShopConnectionEntity shop, int listings);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "productId", source = "product.id")
    @Mapping(target = "productTitle", source = "product.titleZh")
    @Mapping(target = "remoteProductId", source = "remoteProductId")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "lastPushedAt", source = "lastPushedAt")
    ShopListingDtoOut toListing(ShopProductListingEntity listing);
}
