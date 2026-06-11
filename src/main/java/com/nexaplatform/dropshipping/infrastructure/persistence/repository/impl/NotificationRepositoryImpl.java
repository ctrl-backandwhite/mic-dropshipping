package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.model.PlatformNotification;
import com.nexaplatform.dropshipping.domain.repository.NotificationRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.NotificationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.NotificationEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.NotificationJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link NotificationRepository} domain
 * port on top of Spring Data JPA. Owns the persistence-only concern of resolving
 * the {@code user} relation from the flattened {@code userId} on insert, and
 * re-applying the mutable {@code readAt} (and the rest of the fields) onto the
 * managed entity on update. {@code findByUserId} preserves the legacy ordering
 * (newest first).
 */
@Repository
@RequiredArgsConstructor
public class NotificationRepositoryImpl implements NotificationRepository {

    private final NotificationEntityMapper notificationEntityMapper;
    private final NotificationJpaRepositoryAdapter notificationJpaRepositoryAdapter;
    private final UserRepository userRepository;

    @Override
    public PlatformNotification save(PlatformNotification model) {
        NotificationEntity entity = resolveEntity(model);
        applyModel(entity, model);
        NotificationEntity saved = notificationJpaRepositoryAdapter.save(entity);
        return notificationEntityMapper.toDomain(saved);
    }

    @Override
    public PlatformNotification update(PlatformNotification model) {
        return this.save(model);
    }

    @Override
    public List<PlatformNotification> findByUserId(UUID userId) {
        return notificationEntityMapper.toDomainList(
                notificationJpaRepositoryAdapter.findByUser_IdOrderByCreatedAtDesc(userId));
    }

    @Override
    public long countUnreadByUserId(UUID userId) {
        return notificationJpaRepositoryAdapter.countByUser_IdAndReadAtIsNull(userId);
    }

    @Override
    public PlatformNotification getById(UUID id) {
        return notificationJpaRepositoryAdapter.findById(id)
                .map(notificationEntityMapper::toDomain)
                .orElse(null);
    }

    @Override
    public void delete(UUID id) {
        notificationJpaRepositoryAdapter.deleteById(id);
    }

    @Override
    public boolean existsById(UUID id) {
        return notificationJpaRepositoryAdapter.existsById(id);
    }

    /** Loads the managed entity for an existing id, or starts a fresh one for inserts. */
    private NotificationEntity resolveEntity(PlatformNotification model) {
        if (model.getId() != null) {
            return notificationJpaRepositoryAdapter.findById(model.getId())
                    .orElseThrow(() -> new NotFoundException("Notification"));
        }
        return new NotificationEntity();
    }

    /** Applies the mutable model fields onto the entity, resolving the owning user. */
    private void applyModel(NotificationEntity entity, PlatformNotification model) {
        if (entity.getUser() == null) {
            entity.setUser(userRepository.findById(model.getUserId())
                    .orElseThrow(() -> new NotFoundException("User")));
        }
        entity.setEventType(model.getEventType());
        entity.setTitle(model.getTitle());
        entity.setBody(model.getBody());
        if (model.getChannel() != null) {
            entity.setChannel(model.getChannel());
        }
        if (model.getPayload() != null) {
            entity.setPayload(model.getPayload());
        }
        entity.setReadAt(model.getReadAt());
    }
}
