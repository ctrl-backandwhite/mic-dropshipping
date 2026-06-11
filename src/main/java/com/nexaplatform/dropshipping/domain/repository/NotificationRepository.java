package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.PlatformNotification;

import java.util.List;
import java.util.UUID;

/**
 * Domain repository port for {@link PlatformNotification}. Implemented by an
 * infrastructure adapter bridging to Spring Data JPA. Distinct from the legacy
 * Spring Data interface in {@code infrastructure.persistence.repository}.
 */
public interface NotificationRepository extends BaseRepository<PlatformNotification, PlatformNotification, UUID> {

    /** Lists the notifications owned by a user, newest first. */
    List<PlatformNotification> findByUserId(UUID userId);

    /** Counts the unread notifications of a user. */
    long countUnreadByUserId(UUID userId);
}
