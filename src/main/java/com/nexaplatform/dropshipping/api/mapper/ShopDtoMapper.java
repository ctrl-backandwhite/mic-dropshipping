package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.in.ShopConnectDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.ShopDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.ShopInboundSecretDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.ShopListingDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.ShopPlatformDtoOut;
import com.nexaplatform.dropshipping.domain.model.ShopConnection;
import com.nexaplatform.dropshipping.domain.model.ShopInboundSecret;
import com.nexaplatform.dropshipping.domain.model.ShopPlatform;
import com.nexaplatform.dropshipping.domain.model.ShopProductListing;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper for the My Shops boundary: translates between the transport
 * DTOs and the {@link ShopConnection} aggregate (and its {@link ShopProductListing}
 * sub-entity, {@link ShopPlatform} and {@link ShopInboundSecret} value models).
 * Injected in the controller. Preserves the exact JSON field names of the
 * transport DtoOut classes. The raw access token from the DtoIn is carried on the
 * model's {@code accessTokenEnc} and encrypted by the use case before persisting.
 */
@Mapper(componentModel = "spring")
public interface ShopDtoMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "platform", source = "platform")
    @Mapping(target = "shopHandle", source = "shopHandle")
    @Mapping(target = "accessTokenEnc", source = "accessToken")
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "lastSyncAt", ignore = true)
    @Mapping(target = "metadata", ignore = true)
    @Mapping(target = "listings", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    ShopConnection toDomain(ShopConnectDtoIn dtoIn);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "platform", source = "platform")
    @Mapping(target = "shopHandle", source = "shopHandle")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "lastSyncAt", source = "lastSyncAt")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "listings", source = "listings")
    ShopDtoOut toDtoOut(ShopConnection model);

    List<ShopDtoOut> toDtoOutList(List<ShopConnection> models);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "productId", source = "productId")
    @Mapping(target = "productTitle", source = "productTitle")
    @Mapping(target = "remoteProductId", source = "remoteProductId")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "lastPushedAt", source = "lastPushedAt")
    ShopListingDtoOut toListingDtoOut(ShopProductListing model);

    List<ShopListingDtoOut> toListingDtoOutList(List<ShopProductListing> models);

    @Mapping(target = "code", source = "code")
    @Mapping(target = "label", source = "label")
    ShopPlatformDtoOut toPlatformDtoOut(ShopPlatform model);

    List<ShopPlatformDtoOut> toPlatformDtoOutList(List<ShopPlatform> models);

    @Mapping(target = "inboundSecret", source = "inboundSecret")
    @Mapping(target = "inboundUrl", source = "inboundUrl")
    ShopInboundSecretDtoOut toInboundSecretDtoOut(ShopInboundSecret model);
}
