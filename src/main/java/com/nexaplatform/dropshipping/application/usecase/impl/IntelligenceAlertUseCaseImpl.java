package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.application.usecase.IntelligenceAlertUseCase;
import com.nexaplatform.dropshipping.domain.model.IntelligenceAlert;
import com.nexaplatform.dropshipping.domain.repository.IntelligenceAlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Intelligence-alert use case. Holds the logic that used to live in
 * {@code IntelligenceController}: stamping the owner, applying the channel/active
 * defaults on creation and soft-deleting (deactivating) an alert. Operates on the
 * {@link IntelligenceAlert} model and delegates persistence to the domain port.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntelligenceAlertUseCaseImpl implements IntelligenceAlertUseCase {

    private final IntelligenceAlertRepository intelligenceAlertRepository;

    @Override
    @Transactional(readOnly = true)
    public List<IntelligenceAlert> findActiveForUser(UUID userId) {
        return intelligenceAlertRepository.findActiveByUser(userId);
    }

    @Override
    @Transactional
    public IntelligenceAlert create(UUID userId, IntelligenceAlert model) {
        // DROP-71: stamp the owner and apply the channel/active defaults that the
        // controller used to set inline (channel defaults to EMAIL, alert active).
        model.setUserId(userId);
        model.setChannel(Objects.isNull(model.getChannel()) ? "EMAIL" : model.getChannel());
        model.setActive(true);
        IntelligenceAlert saved = intelligenceAlertRepository.save(model);
        log.info("::> [INTELLIGENCE] Alert created id={}", saved.getId());
        return saved;
    }

    @Override
    @Transactional
    public void deactivate(UUID userId, UUID id) {
        // Soft delete: mirror the legacy behaviour of silently ignoring a missing id.
        IntelligenceAlert existing = intelligenceAlertRepository.getById(id);
        // IDOR: la alerta debe pertenecer al usuario. Si no existe o es de otro, se ignora en silencio
        // (mismo 200 neutro que un id inexistente → no revela qué ids ajenos existen).
        if (Objects.isNull(existing) || !Objects.equals(userId, existing.getUserId())) {
            return;
        }
        existing.setActive(false);
        intelligenceAlertRepository.update(existing);
        log.info("::> [INTELLIGENCE] Alert deactivated id={}", id);
    }
}
