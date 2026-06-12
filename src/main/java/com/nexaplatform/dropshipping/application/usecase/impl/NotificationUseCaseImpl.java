package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.application.usecase.NotificationUseCase;
import com.nexaplatform.dropshipping.domain.model.PlatformNotification;
import com.nexaplatform.dropshipping.domain.model.UnreadCount;
import com.nexaplatform.dropshipping.domain.model.User;
import com.nexaplatform.dropshipping.domain.repository.NotificationRepository;
import com.nexaplatform.dropshipping.domain.repository.UserRepository;
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
    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public List<PlatformNotification> myNotifications(UUID userId) {
        return notificationRepository.findByUserId(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public UnreadCount unreadCount(UUID userId) {
        return UnreadCount.builder().count(notificationRepository.countUnreadByUserId(userId)).build();
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

    @Override
    @Transactional
    public int sendAdminNotification(String target, String title, String body) {
        if (target == null || target.isBlank() || "all".equalsIgnoreCase(target.trim())) {
            int n = 0;
            for (User u : userRepository.findAll()) {
                create(u.getId(), title, body, "ADMIN_BROADCAST");
                n++;
            }
            return n;
        }
        User user = userRepository.findByEmail(target.trim().toLowerCase())
                .orElseThrow(() -> new BusinessException("Usuario no encontrado: " + target));
        create(user.getId(), title, body, "ADMIN_MESSAGE");
        return 1;
    }

    private void create(UUID userId, String title, String body, String eventType) {
        notificationRepository.save(PlatformNotification.builder().userId(userId).title(title).body(body)
                .eventType(eventType).channel("IN_APP").build());
    }
}
