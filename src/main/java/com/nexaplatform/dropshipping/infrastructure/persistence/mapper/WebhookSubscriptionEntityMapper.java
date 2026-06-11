package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.WebhookDelivery;
import com.nexaplatform.dropshipping.domain.model.WebhookSubscription;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WebhookDeliveryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WebhookSubscriptionEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Infrastructure-layer mapper between the {@link WebhookSubscription} domain
 * model (plus its nested {@link WebhookDelivery}) and the JPA entities. Builder
 * disabled so MapStruct uses setters and can reach the id/audit fields inherited
 * from {@code BaseEntity}/{@code AuditableEntity}. The {@code user} relation is
 * not part of the domain model and is left untouched on {@code toEntity}.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface WebhookSubscriptionEntityMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "targetUrl", source = "targetUrl")
    @Mapping(target = "secret", source = "secret")
    @Mapping(target = "events", source = "events")
    @Mapping(target = "active", source = "active")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "updatedBy", source = "updatedBy")
    WebhookSubscription toDomain(WebhookSubscriptionEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "name", source = "name")
    @Mapping(target = "targetUrl", source = "targetUrl")
    @Mapping(target = "secret", source = "secret")
    @Mapping(target = "events", source = "events")
    @Mapping(target = "active", source = "active")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    WebhookSubscriptionEntity toEntity(WebhookSubscription model);

    List<WebhookSubscription> toDomainList(List<WebhookSubscriptionEntity> entities);

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
    WebhookDelivery toDeliveryDomain(WebhookDeliveryEntity entity);

    List<WebhookDelivery> toDeliveryDomainList(List<WebhookDeliveryEntity> entities);
}
