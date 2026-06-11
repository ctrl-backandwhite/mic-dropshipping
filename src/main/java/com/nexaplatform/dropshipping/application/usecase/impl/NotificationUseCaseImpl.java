package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.application.usecase.NotificationUseCase;
import com.nexaplatform.dropshipping.domain.model.PlatformNotification;
import com.nexaplatform.dropshipping.domain.model.UnreadCount;
import com.nexaplatform.dropshipping.domain.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Notification use case (DROP-11). Operates on the {@link PlatformNotification}
 * model and delegates persistence to the domain port. Holds the logic that used
 * to live in {@code PlatformExtrasService}: listing, counting unread, and the
 * idempotent mark-read / mark-all-read transitions (a notification already read
 * is left untouched).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationUseCaseImpl implements NotificationUseCase {

    private final NotificationRepository notificationRepository;

    @Override
    @Transactional(readOnly = true)
    public List<PlatformNotification> myNotifications(UUID userId) {
        return notificationRepository.findByUserId(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public UnreadCount unreadCount(UUID userId) {
        return UnreadCount.builder()
                .count(notificationRepository.countUnreadByUserId(userId))
                .build();
    }

    @Override
    @Transactional
    public void markRead(UUID id) {
        PlatformNotification model = notificationRepository.getById(id);
        if (Objects.nonNull(model) && model.getReadAt() == null) {
            model.setReadAt(Instant.now());
            notificationRepository.update(model);
        }
    }

    @Override
    @Transactional
    public void markAllRead(UUID userId) {
        for (PlatformNotification model : notificationRepository.findByUserId(userId)) {
            if (model.getReadAt() == null) {
                model.setReadAt(Instant.now());
                notificationRepository.update(model);
            }
        }
    }
}
