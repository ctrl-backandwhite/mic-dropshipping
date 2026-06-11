package com.nexaplatform.dropshipping.application.mapper;

import com.nexaplatform.dropshipping.domain.model.WebhookSubscription;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

/**
 * Applies a partial update of one {@link WebhookSubscription} onto an existing
 * one, preserving identity, the signing secret and audit. Null object
 * properties are ignored so unspecified fields are not overwritten (mirrors the
 * legacy partial-update contract of the admin webhook update endpoint).
 */
@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface WebhookSubscriptionUpdateMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "secret", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    void updateFromModel(WebhookSubscription source, @MappingTarget WebhookSubscription target);
}
