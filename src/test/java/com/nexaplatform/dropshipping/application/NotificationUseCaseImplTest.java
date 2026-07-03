package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.usecase.impl.NotificationUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.PlatformNotification;
import com.nexaplatform.dropshipping.domain.model.UnreadCount;
import com.nexaplatform.dropshipping.domain.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationUseCaseImplTest {

    @Mock
    NotificationRepository notificationRepository;
    @InjectMocks
    NotificationUseCaseImpl useCase;

    @Test
    void unreadCount_wrapsRepositoryCounter() {
        UUID userId = UUID.randomUUID();
        when(notificationRepository.countUnreadByUserId(userId)).thenReturn(7L);

        UnreadCount result = useCase.unreadCount(userId);

        assertThat(result.getCount()).isEqualTo(7L);
    }

    @Test
    void markRead_setsReadAtWhenUnread() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        PlatformNotification model = PlatformNotification.builder().id(id).userId(userId).build();
        when(notificationRepository.getById(id)).thenReturn(model);

        useCase.markRead(id, userId);

        assertThat(model.getReadAt()).isNotNull();
        verify(notificationRepository).update(model);
    }

    @Test
    void markRead_isNoOpWhenAlreadyRead() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        // Estado coherente (leída + RECEIVED): markRead no debe cambiar nada.
        PlatformNotification model = PlatformNotification.builder().id(id).userId(userId)
                .readAt(Instant.now()).status("RECEIVED").build();
        when(notificationRepository.getById(id)).thenReturn(model);

        useCase.markRead(id, userId);

        verify(notificationRepository, never()).update(model);
    }

    @Test
    void markRead_isNoOpForOtherUsersNotification() {
        UUID id = UUID.randomUUID();
        PlatformNotification model = PlatformNotification.builder().id(id).userId(UUID.randomUUID()).build();
        when(notificationRepository.getById(id)).thenReturn(model);

        useCase.markRead(id, UUID.randomUUID()); // otro usuario → no-op (IDOR)

        verify(notificationRepository, never()).update(model);
    }
}
