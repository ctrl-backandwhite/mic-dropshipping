package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.PartnerWebhookDispatcherService;
import com.nexaplatform.dropshipping.application.service.PublicHttpUrl;
import com.nexaplatform.dropshipping.application.service.WebhookDispatcherService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WebhookDeliveryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.WebhookDeliveryRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.WebhookSubscriptionRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookDispatcherServiceTest {

    @BeforeAll
    static void permitirDestinosLocales() {
        // Estos casos llaman a un servidor de pruebas en 127.0.0.1, que la protección anti-SSRF rechaza
        // por diseño. Se abre aquí y se cierra al terminar, para no dejarlo abierto a otros tests.
        PublicHttpUrl.allowPrivateTargets(true);
    }

    @AfterAll
    static void restaurarProteccion() {
        PublicHttpUrl.allowPrivateTargets(false);
    }

    @Mock
    WebhookSubscriptionRepository subscriptionRepository;
    @Mock
    WebhookDeliveryRepository deliveryRepository;
    @Mock
    PartnerWebhookDispatcherService partnerWebhooks;
    @InjectMocks
    WebhookDispatcherService service;

    @Test
    void sign_matchesIndependentHmacAndDiffersBySecret() throws Exception {
        String body = "{\"hello\":\"world\"}";
        assertThat(WebhookDispatcherService.sign(body, "s3cr3t")).isEqualTo(hmac(body, "s3cr3t"));
        assertThat(WebhookDispatcherService.sign(body, "s3cr3t"))
                .isNotEqualTo(WebhookDispatcherService.sign(body, "other"));
    }

    @Test
    void publish_fansOutToPartnersAndSavesNothingWithoutSubscriptions() {
        when(subscriptionRepository.findByActiveTrue()).thenReturn(List.of());

        service.publish("order.created", "evt-1", Map.of("orderId", "1"));

        verify(partnerWebhooks).publish("order.created", "evt-1", Map.of("orderId", "1"));
        verify(deliveryRepository, never()).save(any());
    }

    @Test
    void attempt_schedulesRetryWithFirstBackoffOnFailure() {
        UUID id = UUID.randomUUID();
        WebhookDeliveryEntity d = delivery(0);
        when(deliveryRepository.findById(id)).thenReturn(Optional.of(d));
        when(deliveryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.attempt(id); // URL muerta → fallo → primer backoff (60s)

        assertThat(d.getAttempt()).isEqualTo(1);
        assertThat(d.getStatus()).isEqualTo("RETRY");
        assertThat(d.getNextRetryAt()).isBetween(Instant.now().plus(50, ChronoUnit.SECONDS),
                Instant.now().plus(70, ChronoUnit.SECONDS));
    }

    @Test
    void attempt_marksFailedAfterMaxAttempts() {
        UUID id = UUID.randomUUID();
        WebhookDeliveryEntity d = delivery(4); // tras incrementar = 5 = MAX_ATTEMPTS
        when(deliveryRepository.findById(id)).thenReturn(Optional.of(d));
        when(deliveryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.attempt(id);

        assertThat(d.getAttempt()).isEqualTo(5);
        assertThat(d.getStatus()).isEqualTo("FAILED");
        assertThat(d.getNextRetryAt()).isNull();
    }

    private static WebhookDeliveryEntity delivery(int attempt) {
        return WebhookDeliveryEntity.builder().eventType("order.created").eventId("evt-1").payload(Map.of("k", "v"))
                .signature("sig").targetUrl("http://127.0.0.1:1/hook").status("PENDING").attempt(attempt).build();
    }

    private static String hmac(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
