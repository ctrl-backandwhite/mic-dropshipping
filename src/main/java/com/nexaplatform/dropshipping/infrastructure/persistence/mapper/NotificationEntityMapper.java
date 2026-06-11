package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.PlatformNotification;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.NotificationEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Infrastructure-layer mapper between the {@link PlatformNotification} domain
 * model and the JPA entity. Builder disabled so MapStruct uses setters and can
 * reach the id/audit fields inherited from {@code BaseEntity}/{@code AuditableEntity}.
 * The {@code user} relation is resolved by the repository adapter (which owns the
 * managed entity), so it is ignored on {@code toEntity}; the domain side carries
 * the flattened {@code userId}.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface NotificationEntityMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "userId", source = "user.id")
    @Mapping(target = "eventType", source = "eventType")
    @Mapping(target = "title", source = "title")
    @Mapping(target = "body", source = "body")
    @Mapping(target = "channel", source = "channel")
    @Mapping(target = "payload", source = "payload")
    @Mapping(target = "readAt", source = "readAt")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "updatedBy", source = "updatedBy")
    PlatformNotification toDomain(NotificationEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "eventType", source = "eventType")
    @Mapping(target = "title", source = "title")
    @Mapping(target = "body", source = "body")
    @Mapping(target = "channel", source = "channel")
    @Mapping(target = "payload", source = "payload")
    @Mapping(target = "readAt", source = "readAt")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    NotificationEntity toEntity(PlatformNotification model);

    List<PlatformNotification> toDomainList(List<NotificationEntity> entities);
}
