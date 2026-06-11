package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.domain.model.PlatformNotification;
import com.nexaplatform.dropshipping.domain.model.UnreadCount;

import java.util.List;
import java.util.UUID;

/**
 * Use-case port for the notification aggregate (DROP-11). Operates on the
 * {@link PlatformNotification} domain model; operations are scoped to the owning
 * {@code userId}.
 */
public interface NotificationUseCase {

    /** Lists the user's notifications, newest first. */
    List<PlatformNotification> myNotifications(UUID userId);

    /** Returns the user's unread-notification count. */
    UnreadCount unreadCount(UUID userId);

    /** Marks a single notification as read (no-op if already read or missing). */
    void markRead(UUID id);

    /** Marks all of the user's notifications as read. */
    void markAllRead(UUID userId);
}
