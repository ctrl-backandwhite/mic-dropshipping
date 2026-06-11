package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.in.WebhookSubscriptionUpdateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.WebhookDeliveryDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.WebhookSubscriptionDtoOut;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WebhookDeliveryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WebhookSubscriptionEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.util.List;

/**
 * MapStruct mapper for the Admin Webhooks API boundary. Converts JPA entities
 * into DTOs and applies partial updates from DtoIn. Combined with Lombok:
 * entity getters/setters and DtoOut builders are Lombok-generated and consumed
 * by the MapStruct-generated implementation.
 */
@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface AdminWebhookMapper {

    WebhookSubscriptionDtoOut toSubscriptionDto(WebhookSubscriptionEntity entity);

    List<WebhookSubscriptionDtoOut> toSubscriptionDtos(List<WebhookSubscriptionEntity> entities);

    WebhookDeliveryDtoOut toDeliveryDto(WebhookDeliveryEntity entity);

    List<WebhookDeliveryDtoOut> toDeliveryDtos(List<WebhookDeliveryEntity> entities);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "secret", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateSubscriptionFromDto(WebhookSubscriptionUpdateDtoIn dto, @MappingTarget WebhookSubscriptionEntity entity);
}
