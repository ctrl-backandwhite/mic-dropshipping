package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.in.WebhookSubscriptionCreateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.WebhookSubscriptionUpdateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.WebhookDeliveryDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.WebhookSubscriptionDtoOut;
import com.nexaplatform.dropshipping.domain.model.WebhookDelivery;
import com.nexaplatform.dropshipping.domain.model.WebhookSubscription;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper for webhook subscriptions: translates between the transport
 * DTOs and the {@link WebhookSubscription} domain model (plus the nested
 * {@link WebhookDelivery}). Injected in the controller. Keeps the exact DtoOut
 * field names (frontend contract).
 */
@Mapper(componentModel = "spring")
public interface WebhookSubscriptionDtoMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "targetUrl", source = "targetUrl")
    @Mapping(target = "secret", source = "secret")
    @Mapping(target = "events", source = "events")
    @Mapping(target = "active", source = "active")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "createdAt", source = "createdAt")
    WebhookSubscriptionDtoOut toDtoOut(WebhookSubscription model);

    List<WebhookSubscriptionDtoOut> toDtoOutList(List<WebhookSubscription> models);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "eventType", source = "eventType")
    @Mapping(target = "eventId", source = "eventId")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "attempt", source = "attempt")
    @Mapping(target = "responseStatus", source = "responseStatus")
    @Mapping(target = "responseBody", source = "responseBody")
    @Mapping(target = "lastAttemptAt", source = "lastAttemptAt")
    @Mapping(target = "nextRetryAt", source = "nextRetryAt")
    @Mapping(target = "createdAt", source = "createdAt")
    WebhookDeliveryDtoOut toDeliveryDtoOut(WebhookDelivery model);

    List<WebhookDeliveryDtoOut> toDeliveryDtoOutList(List<WebhookDelivery> models);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "secret", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "name", source = "name")
    @Mapping(target = "targetUrl", source = "targetUrl")
    @Mapping(target = "events", expression = "java(dtoIn.getEvents() == null ? new java.util.ArrayList<>() : dtoIn.getEvents())")
    @Mapping(target = "active", constant = "true")
    @Mapping(target = "description", source = "description")
    WebhookSubscription toDomain(WebhookSubscriptionCreateDtoIn dtoIn);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "secret", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "name", source = "name")
    @Mapping(target = "targetUrl", source = "targetUrl")
    @Mapping(target = "events", source = "events")
    @Mapping(target = "active", expression = "java(dtoIn.getActive() != null && dtoIn.getActive())")
    @Mapping(target = "description", source = "description")
    WebhookSubscription toDomain(WebhookSubscriptionUpdateDtoIn dtoIn);
}
