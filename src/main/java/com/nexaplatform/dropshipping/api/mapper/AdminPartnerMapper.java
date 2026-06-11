package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.AdminOAuthClientDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminPartnerAppDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminShopConnectionDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminPartnerWebhookDtoOut;
import com.nexaplatform.dropshipping.domain.model.AdminOAuthClient;
import com.nexaplatform.dropshipping.domain.model.AdminPartnerApp;
import com.nexaplatform.dropshipping.domain.model.AdminShopConnection;
import com.nexaplatform.dropshipping.domain.model.AdminPartnerWebhook;
import org.mapstruct.Mapper;

import java.util.List;

/**
 * API-layer mapper for the Admin Partners resource: translates the read-projection
 * domain models into their transport DtoOuts. Injected in the controller. DtoOut
 * field names (via @JsonProperty) preserve the exact JSON keys / column labels the
 * frontend already consumes; these are pure reads, so no toDomain direction exists.
 */
@Mapper(componentModel = "spring")
public interface AdminPartnerMapper {

    AdminOAuthClientDtoOut toDtoOut(AdminOAuthClient model);

    List<AdminOAuthClientDtoOut> toOAuthClientDtoOutList(List<AdminOAuthClient> models);

    AdminPartnerWebhookDtoOut toDtoOut(AdminPartnerWebhook model);

    List<AdminPartnerWebhookDtoOut> toWebhookDtoOutList(List<AdminPartnerWebhook> models);

    AdminPartnerAppDtoOut toDtoOut(AdminPartnerApp model);

    List<AdminPartnerAppDtoOut> toPartnerAppDtoOutList(List<AdminPartnerApp> models);

    AdminShopConnectionDtoOut toDtoOut(AdminShopConnection model);

    List<AdminShopConnectionDtoOut> toShopConnectionDtoOutList(List<AdminShopConnection> models);
}
