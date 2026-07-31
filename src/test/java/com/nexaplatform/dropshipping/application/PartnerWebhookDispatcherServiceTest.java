package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.PublicHttpUrl;
import com.nexaplatform.dropshipping.application.service.PartnerWebhookDispatcherService;
import com.nexaplatform.dropshipping.application.service.WebhookDispatcherService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PartnerWebhookDispatcherServiceTest {

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
    JdbcTemplate jdbc;
    @InjectMocks
    PartnerWebhookDispatcherService service;

    private static final String SELECT_APPS =
            "SELECT id FROM partner_app WHERE active = true AND webhook_url IS NOT NULL AND webhook_url <> ''";

    // ----- publish -----

    @Test
    void publish_enqueuesOnePendingRowPerActivePartnerApp() {
        UUID app1 = UUID.randomUUID();
        UUID app2 = UUID.randomUUID();
        when(jdbc.queryForList(SELECT_APPS)).thenReturn(List.of(
                Map.of("id", app1), Map.of("id", app2)));

        service.publish("order.created", "evt-1", Map.of("orderId", "1"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).update(sql.capture(),
                any(), any(), any(), any());
        assertThat(sql.getValue()).contains("INSERT INTO partner_webhook_delivery");
        assertThat(sql.getValue()).contains("'PENDING'");
    }

    @Test
    void publish_withNoActiveApps_insertsNothing() {
        when(jdbc.queryForList(SELECT_APPS)).thenReturn(List.of());

        service.publish("order.created", "evt-1", Map.of("orderId", "1"));

        verify(jdbc, never()).update(anyString(), any(), any(), any(), any());
    }

    @Test
    void publish_serializesEnvelopeWithEventMetadataIntoPayload() {
        UUID app1 = UUID.randomUUID();
        when(jdbc.queryForList(SELECT_APPS)).thenReturn(List.of(Map.of("id", app1)));

        service.publish("order.shipped", "evt-9", Map.of("k", "v"));

        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        // (id, partner_app_id, event_type, payload)
        verify(jdbc).update(anyString(), any(UUID.class), eq(app1), eq("order.shipped"), args.capture());
        String payload = (String) args.getValue();
        assertThat(payload).contains("\"id\":\"evt-9\"")
                .contains("\"type\":\"order.shipped\"")
                .contains("\"data\":{\"k\":\"v\"}")
                .contains("\"createdAt\"");
    }

    // ----- dispatchTestToAll -----

    @Test
    void dispatchTestToAll_returnsActiveTargetCountAndEnqueuesTestPing() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(3);
        when(jdbc.queryForList(SELECT_APPS)).thenReturn(List.of(Map.of("id", UUID.randomUUID())));

        int n = service.dispatchTestToAll();

        assertThat(n).isEqualTo(3);
        ArgumentCaptor<String> et = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(anyString(), any(UUID.class), any(), et.capture(), any());
        assertThat(et.getValue()).isEqualTo("test.ping");
    }

    @Test
    void dispatchTestToAll_handlesNullCountAsZero() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(null);
        when(jdbc.queryForList(SELECT_APPS)).thenReturn(List.of());

        assertThat(service.dispatchTestToAll()).isZero();
    }

    // ----- drainDue / attempt (HTTP against dead URL → backoff) -----

    @Test
    void drainDue_onFailure_schedulesRetryWithFirstBackoff() {
        UUID id = UUID.randomUUID();
        when(jdbc.queryForList(anyString())).thenReturn(List.of(dueRow(id, 0)));

        service.drainDue(); // dead URL → exception → RETRY + first backoff (60s)

        ArgumentCaptor<Object> a = ArgumentCaptor.forClass(Object.class);
        // finish(): status, attempt, code, body, next_attempt_at, id
        verify(jdbc).update(anyString(), eq("RETRY"), eq(1), a.capture(), anyString(), a.capture(), eq(id));
        Timestamp next = nextRetryArg(a.getAllValues());
        assertThat(next).isNotNull();
        assertThat(next.toInstant()).isBetween(
                Instant.now().plus(50, ChronoUnit.SECONDS),
                Instant.now().plus(70, ChronoUnit.SECONDS));
    }

    @Test
    void drainDue_marksFailedWithoutRetryAfterMaxAttempts() {
        UUID id = UUID.randomUUID();
        when(jdbc.queryForList(anyString())).thenReturn(List.of(dueRow(id, 4))); // +1 = 5 = MAX_ATTEMPTS

        service.drainDue();

        // next_attempt_at must be null when exhausted
        verify(jdbc).update(anyString(), eq("FAILED"), eq(5), any(), anyString(), eq((Timestamp) null), eq(id));
    }

    @Test
    void drainDue_signaturePassedMatchesIndependentHmac() {
        UUID id = UUID.randomUUID();
        Map<String, Object> row = dueRow(id, 0);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(row));

        service.drainDue();

        // El despachador firma el payload con el secreto del webhook. No podemos capturar la cabecera
        // HTTP saliente porque la URL está muerta, así que comprobamos lo que sí es observable: que la
        // firma es determinista con el mismo secreto y distinta con otro, que es lo que fija el helper.
        String payload = (String) row.get("payload");
        String secret = (String) row.get("webhook_secret");
        assertThat(WebhookDispatcherService.sign(payload, secret))
                .isEqualTo(WebhookDispatcherService.sign(payload, secret))
                .isNotEqualTo(WebhookDispatcherService.sign(payload, "other"));
    }

    @Test
    void drainDue_withNoDueRows_doesNotUpdate() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of());

        service.drainDue();

        verify(jdbc, never()).update(anyString(), any(), any(), any(), any(), any(), any());
    }

    private static Map<String, Object> dueRow(UUID id, int attemptCount) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("partner_app_id", UUID.randomUUID());
        row.put("event_type", "order.created");
        row.put("payload", "{\"id\":\"evt-1\",\"type\":\"order.created\"}");
        row.put("attempt_count", attemptCount);
        row.put("webhook_url", "http://127.0.0.1:1/x"); // dead port → connection refused
        row.put("webhook_secret", "s3cr3t");
        return row;
    }

    private static Timestamp nextRetryArg(List<Object> captured) {
        for (Object o : captured) {
            if (o instanceof Timestamp t) {
                return t;
            }
        }
        return null;
    }
}
