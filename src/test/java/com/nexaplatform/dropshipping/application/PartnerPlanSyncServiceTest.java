package com.nexaplatform.dropshipping.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.application.service.PartnerPlanSyncService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerSubscriptionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SubscriptionPlanEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CustomerSubscriptionRepository;
import com.nexaplatform.dropshipping.infrastructure.security.oauth.JwtRevocationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PartnerPlanSyncServiceTest {

    @Mock
    CustomerSubscriptionRepository subsRepo;
    @Mock
    JdbcTemplate jdbc;
    @Mock
    JwtRevocationService revocationService;
    // Mapper real inyectado por @InjectMocks: debe ser @Spy, no un campo plano, o el servicio lo
    // recibiría como null (constructor @RequiredArgsConstructor) y readValue lanzaría NPE.
    @org.mockito.Spy
    ObjectMapper mapper = new ObjectMapper();

    @InjectMocks
    PartnerPlanSyncService service;

    // ----- syncForUser: tier recalculation -----

    @Test
    void syncForUser_withPaidPlan_writesPaidTierAndPlanCodeToClientSettings() throws Exception {
        UUID userId = UUID.randomUUID();
        when(subsRepo.findActiveByUserId(userId)).thenReturn(List.of(sub("PRO")));
        when(jdbc.queryForList(anyString(), eq(userId.toString())))
                .thenReturn(List.of(clientRow("cli-1", "{}")));

        service.syncForUser(userId);

        Map<String, Object> written = capturedSettings();
        assertThat(written)
                .containsEntry("nexadrop.plan", "paid")
                .containsEntry("nexadrop.plan_code", "PRO")
                .hasEntrySatisfying("nexadrop.plan_synced_at", syncedAt -> assertThat(syncedAt).isNotNull());
        verify(revocationService).revokeAllForClients(List.of("cli-1"));
    }

    @Test
    void syncForUser_withFreePlan_mapsToSandboxTier() throws Exception {
        UUID userId = UUID.randomUUID();
        when(subsRepo.findActiveByUserId(userId)).thenReturn(List.of(sub("FREE")));
        when(jdbc.queryForList(anyString(), eq(userId.toString())))
                .thenReturn(List.of(clientRow("cli-1", "{}")));

        service.syncForUser(userId);

        Map<String, Object> written = capturedSettings();
        assertThat(written)
                .containsEntry("nexadrop.plan", "sandbox")
                .containsEntry("nexadrop.plan_code", "FREE");
    }

    @Test
    void syncForUser_withNoActiveSubscription_downgradesToSandboxWithoutPlanCode() throws Exception {
        UUID userId = UUID.randomUUID();
        when(subsRepo.findActiveByUserId(userId)).thenReturn(List.of());
        when(jdbc.queryForList(anyString(), eq(userId.toString())))
                .thenReturn(List.of(clientRow("cli-1", "{\"nexadrop.plan\":\"paid\",\"nexadrop.plan_code\":\"PRO\"}")));

        service.syncForUser(userId);

        Map<String, Object> written = capturedSettings();
        assertThat(written)
                .containsEntry("nexadrop.plan", "sandbox")
                // planCode is null → key left as the previous value, never overwritten
                .containsEntry("nexadrop.plan_code", "PRO");
    }

    @Test
    void syncForUser_withNoOAuthClients_doesNotRevokeAnything() {
        UUID userId = UUID.randomUUID();
        when(subsRepo.findActiveByUserId(userId)).thenReturn(List.of(sub("STARTER")));
        when(jdbc.queryForList(anyString(), eq(userId.toString()))).thenReturn(List.of());

        service.syncForUser(userId);

        verify(jdbc, never()).update(anyString(), any(), any());
        verify(revocationService, never()).revokeAllForClients(anyList());
    }

    @Test
    void syncForUser_skipsRowWithUnparseableSettingsButStillProcessesOthers() throws Exception {
        UUID userId = UUID.randomUUID();
        when(subsRepo.findActiveByUserId(userId)).thenReturn(List.of(sub("ENTERPRISE")));
        when(jdbc.queryForList(anyString(), eq(userId.toString()))).thenReturn(List.of(
                clientRow("bad", "not-json"),
                clientRow("good", "{}")));

        service.syncForUser(userId);

        // only the parseable client gets an UPDATE + revocation
        verify(jdbc).update(anyString(), anyString(), eq("good-row-id"));
        verify(revocationService).revokeAllForClients(List.of("good"));
    }

    // ----- onSubscriptionEvent: stripe id resolution -----

    @Test
    void onSubscriptionEvent_unknownStripeId_isNoOp() {
        when(subsRepo.findByStripeSubscriptionId("sub_X")).thenReturn(Optional.empty());

        service.onSubscriptionEvent("sub_X", "active", "customer.subscription.updated");

        verify(subsRepo, never()).findActiveByUserId(any());
        verifyNoInteractions(jdbc);
        verifyNoInteractions(revocationService);
    }

    @Test
    void onSubscriptionEvent_knownStripeId_resolvesUserAndSyncs() throws Exception {
        UUID userId = UUID.randomUUID();
        CustomerSubscriptionEntity sub = sub("PRO");
        UserEntity user = new UserEntity();
        user.setId(userId);
        sub.setUser(user);
        when(subsRepo.findByStripeSubscriptionId("sub_K")).thenReturn(Optional.of(sub));
        when(subsRepo.findActiveByUserId(userId)).thenReturn(List.of(sub("PRO")));
        when(jdbc.queryForList(anyString(), eq(userId.toString())))
                .thenReturn(List.of(clientRow("cli-1", "{}")));

        service.onSubscriptionEvent("sub_K", "active", "customer.subscription.updated");

        Map<String, Object> written = capturedSettings();
        assertThat(written).containsEntry("nexadrop.plan", "paid");
        verify(revocationService).revokeAllForClients(List.of("cli-1"));
    }

    // ----- helpers -----

    private static CustomerSubscriptionEntity sub(String planCode) {
        SubscriptionPlanEntity plan = SubscriptionPlanEntity.builder().code(planCode).name(planCode).build();
        return CustomerSubscriptionEntity.builder().plan(plan).build();
    }

    private static Map<String, Object> clientRow(String clientId, String settingsJson) {
        Map<String, Object> r = new HashMap<>();
        r.put("id", clientId.equals("good") ? "good-row-id" : clientId + "-row-id");
        r.put("client_id", clientId);
        r.put("client_settings", settingsJson);
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedSettings() throws Exception {
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(anyString(), json.capture(), any());
        return mapper.readValue(json.getValue(), Map.class);
    }
}
