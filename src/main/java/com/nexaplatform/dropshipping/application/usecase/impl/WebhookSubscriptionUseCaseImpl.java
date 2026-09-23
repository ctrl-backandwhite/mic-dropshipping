package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.mapper.WebhookSubscriptionUpdateMapper;
import com.nexaplatform.dropshipping.application.service.WebhookDispatcherService;
import com.nexaplatform.dropshipping.application.usecase.WebhookSubscriptionUseCase;
import com.nexaplatform.dropshipping.domain.model.WebhookDelivery;
import com.nexaplatform.dropshipping.domain.model.WebhookSubscription;
import com.nexaplatform.dropshipping.domain.repository.WebhookSubscriptionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WebhookDeliveryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.WebhookSubscriptionEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.WebhookDeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Webhook-subscription use case. Operates on the {@link WebhookSubscription}
 * model and delegates persistence to the domain port. Carries the secret
 * lifecycle (generated on create, rotated on demand) and the command operations
 * that dispatch via {@link WebhookDispatcherService}. Delivery listing reuses
 * the legacy Spring Data delivery repository as a read-only collaborator.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookSubscriptionUseCaseImpl implements WebhookSubscriptionUseCase {

    private static final int MAX_DELIVERIES = 50;

    private final WebhookSubscriptionRepository webhookSubscriptionRepository;
    private final WebhookSubscriptionUpdateMapper webhookSubscriptionUpdateMapper;
    private final WebhookSubscriptionEntityMapper webhookSubscriptionEntityMapper;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookDispatcherService dispatcher;
    private final SecureRandom rnd = new SecureRandom();

    @Override
    @Transactional
    public WebhookSubscription save(WebhookSubscription model) {
        model.setSecret(generateSecret());
        model.setActive(true);
        WebhookSubscription saved = webhookSubscriptionRepository.save(model);
        log.info("::> [WEBHOOK] Subscription created id={}", saved.getId());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<WebhookSubscription> findAll() {
        return webhookSubscriptionRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public WebhookSubscription getById(UUID id) {
        return requireById(id);
    }

    /**
     * Búsqueda por id sin anotación transaccional, para que la usen los métodos de escritura de esta
     * misma clase: llamando a {@link #getById(UUID)} con {@code this} la autoinvocación no pasa por el
     * proxy, así que su {@code @Transactional} no se aplicaba y la anotación prometía algo que nadie
     * cumplía. La transacción la abre siempre el método público de entrada.
     */
    private WebhookSubscription requireById(UUID id) {
        WebhookSubscription model = webhookSubscriptionRepository.getById(id);
        if (Objects.isNull(model)) {
            throw new NotFoundException("Subscription");
        }
        return model;
    }

    @Override
    @Transactional
    public WebhookSubscription update(WebhookSubscription model, UUID id) {
        WebhookSubscription existing = requireById(id);
        webhookSubscriptionUpdateMapper.updateFromModel(model, existing);
        WebhookSubscription saved = webhookSubscriptionRepository.update(existing);
        log.info("::> [WEBHOOK] Subscription updated id={}", id);
        return saved;
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        webhookSubscriptionRepository.delete(id);
        log.info("::> [WEBHOOK] Subscription deleted id={}", id);
    }

    @Override
    @Transactional
    public WebhookSubscription rotate(UUID id) {
        WebhookSubscription existing = requireById(id);
        existing.setSecret(generateSecret());
        WebhookSubscription saved = webhookSubscriptionRepository.update(existing);
        log.info("::> [WEBHOOK] Subscription secret rotated id={}", id);
        return saved;
    }

    @Override
    @Transactional
    public WebhookSubscription fireTest(UUID id) {
        WebhookSubscription existing = requireById(id);
        dispatcher.publishTest(existing.getId());
        log.info("::> [WEBHOOK] Test delivery fired for subscription id={}", id);
        return existing;
    }

    @Override
    @Transactional(readOnly = true)
    public List<WebhookDelivery> deliveries(UUID id) {
        List<WebhookDeliveryEntity> rows = deliveryRepository.findBySubscription_IdOrderByCreatedAtDesc(id).stream()
                .limit(MAX_DELIVERIES).toList();
        return webhookSubscriptionEntityMapper.toDeliveryDomainList(rows);
    }

    private String generateSecret() {
        byte[] buf = new byte[32];
        rnd.nextBytes(buf);
        return "whsec_" + HexFormat.of().formatHex(buf);
    }
}
