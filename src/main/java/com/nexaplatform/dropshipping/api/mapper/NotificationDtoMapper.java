package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.PlatformNotificationDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.UnreadCountDtoOut;
import com.nexaplatform.dropshipping.domain.model.PlatformNotification;
import com.nexaplatform.dropshipping.domain.model.UnreadCount;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper for the notification aggregate: translates the
 * {@link PlatformNotification} / {@link UnreadCount} domain models into the
 * transport DTOs. Injected in the controller. DtoOut field names preserve the
 * exact JSON keys the frontend already consumes.
 */
@Mapper(componentModel = "spring")
public interface NotificationDtoMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "eventType", source = "eventType")
    @Mapping(target = "title", source = "title")
    @Mapping(target = "body", source = "body")
    @Mapping(target = "channel", source = "channel")
    @Mapping(target = "payload", source = "payload")
    @Mapping(target = "readAt", source = "readAt")
    @Mapping(target = "createdAt", source = "createdAt")
    PlatformNotificationDtoOut toDtoOut(PlatformNotification model);

    List<PlatformNotificationDtoOut> toDtoOutList(List<PlatformNotification> models);

    @Mapping(target = "count", source = "count")
    UnreadCountDtoOut toUnreadDtoOut(UnreadCount model);
}
