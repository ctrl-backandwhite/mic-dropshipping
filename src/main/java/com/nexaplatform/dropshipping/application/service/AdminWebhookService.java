package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.in.WebhookSubscriptionCreateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.WebhookSubscriptionUpdateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.WebhookDeliveryDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.WebhookSubscriptionDtoOut;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.AdminWebhookMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WebhookSubscriptionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.WebhookDeliveryRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.WebhookSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminWebhookService {

    private static final int MAX_DELIVERIES = 50;

    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookDispatcherService dispatcher;
    private final AdminWebhookMapper mapper;
    private final SecureRandom rnd = new SecureRandom();

    @Transactional(readOnly = true)
    public List<WebhookSubscriptionDtoOut> list() {
        List<WebhookSubscriptionEntity> sorted = subscriptionRepository.findAll().stream()
                .sorted(Comparator.comparing(WebhookSubscriptionEntity::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        return mapper.toSubscriptionDtos(sorted);
    }

    @Transactional
    public WebhookSubscriptionDtoOut create(WebhookSubscriptionCreateDtoIn req) {
        WebhookSubscriptionEntity s = WebhookSubscriptionEntity.builder()
                .name(req.getName())
                .targetUrl(req.getTargetUrl())
                .events(req.getEvents() == null ? List.of() : req.getEvents())
                .secret(generateSecret())
                .description(req.getDescription())
                .active(true)
                .build();
        return mapper.toSubscriptionDto(subscriptionRepository.save(s));
    }

    @Transactional
    public WebhookSubscriptionDtoOut update(UUID id, WebhookSubscriptionUpdateDtoIn req) {
        WebhookSubscriptionEntity s = subscriptionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Subscription"));
        mapper.updateSubscriptionFromDto(req, s);
        return mapper.toSubscriptionDto(subscriptionRepository.save(s));
    }

    @Transactional
    public WebhookSubscriptionDtoOut rotate(UUID id) {
        WebhookSubscriptionEntity s = subscriptionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Subscription"));
        s.setSecret(generateSecret());
        return mapper.toSubscriptionDto(subscriptionRepository.save(s));
    }

    @Transactional
    public void delete(UUID id) {
        subscriptionRepository.deleteById(id);
    }

    @Transactional
    public WebhookSubscriptionDtoOut fireTest(UUID id) {
        WebhookSubscriptionEntity s = subscriptionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Subscription"));
        dispatcher.publishTest(s);
        return mapper.toSubscriptionDto(s);
    }

    @Transactional(readOnly = true)
    public List<WebhookDeliveryDtoOut> deliveries(UUID id) {
        var rows = deliveryRepository.findBySubscription_IdOrderByCreatedAtDesc(id).stream()
                .limit(MAX_DELIVERIES)
                .toList();
        return mapper.toDeliveryDtos(rows);
    }

    private String generateSecret() {
        byte[] buf = new byte[32];
        rnd.nextBytes(buf);
        return "whsec_" + HexFormat.of().formatHex(buf);
    }
}
