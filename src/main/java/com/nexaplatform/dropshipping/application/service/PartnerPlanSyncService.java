package com.nexaplatform.dropshipping.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerSubscriptionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CustomerSubscriptionRepository;
import com.nexaplatform.dropshipping.infrastructure.security.oauth.JwtRevocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cuando Stripe confirma o cancela una suscripción, sincronizamos el setting
 * `nexadrop.plan` (override) de los OAuth clients del usuario afectado.
 *
 * Nota sobre tokens ya emitidos: son JWT autocontenidos de 12 h. No se pueden
 * revocar individualmente — el cambio se refleja en el próximo refresh. Si se
 * necesita revocación inmediata, habría que mantener una lista jti en Redis y
 * que el ResourceServer la consulte (no implementado aquí).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartnerPlanSyncService {

    // Literales repetidos extraídos a constantes (java:S1192): una sola fuente por valor.
    private static final String SANDBOX = "sandbox";

    private final CustomerSubscriptionRepository subsRepo;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final JwtRevocationService revocationService;

    /** Mapeo plan.code → tier (debe estar alineado con partnerPlanClaimCustomizer). */
    private static String mapPlanCodeToTier(String planCode) {
        if (planCode == null)
            return SANDBOX;
        return switch (planCode.toUpperCase()) {
            case "FREE" -> SANDBOX;
            case "STARTER", "PRO", "ENTERPRISE" -> "paid";
            default -> SANDBOX;
        };
    }

    /**
     * Recalcula el tier efectivo de un usuario en base a sus suscripciones
     * activas y actualiza el setting `nexadrop.plan` en sus OAuth clients.
     */
    @Transactional
    public void syncForUser(UUID userId) {
        doSyncForUser(userId);
    }

    /**
     * Cuerpo de la sincronización, sin anotar: lo llama también el webhook, que ya está dentro de su
     * transacción. Invocarlo con {@code this.syncForUser(...)} se saltaba el proxy de Spring, así que
     * aquella {@code @Transactional} no llegaba a aplicarse (java:S6809); ahora la anotación queda solo
     * en los puntos de entrada públicos, que es donde de verdad actúa.
     */
    private void doSyncForUser(UUID userId) {
        List<CustomerSubscriptionEntity> active = subsRepo.findActiveByUserId(userId);
        String tier = active.isEmpty() ? SANDBOX : mapPlanCodeToTier(active.get(0).getPlan().getCode());
        int updated = updateClientSettings(userId, tier, active.isEmpty() ? null : active.get(0).getPlan().getCode());
        log.info("Plan sync user={} tier={} clients_updated={}", userId, tier, updated);
    }

    /** Llamado por el webhook de Stripe. Resuelve el userId via stripe_subscription_id. */
    @Transactional
    public void onSubscriptionEvent(String stripeSubscriptionId, String stripeStatus, String eventType) {
        CustomerSubscriptionEntity sub = subsRepo.findByStripeSubscriptionId(stripeSubscriptionId).orElse(null);
        if (sub == null) {
            log.warn("Stripe subscription event {} for unknown stripe_id={}", eventType, stripeSubscriptionId);
            return;
        }
        doSyncForUser(sub.getUser().getId());
    }

    /**
     * Lee los oauth2_registered_client del usuario y reescribe el JSON `client_settings`
     * con el nuevo plan + timestamp. Devuelve cuántos clients se actualizaron.
     */
    @SuppressWarnings("unchecked")
    private int updateClientSettings(UUID userId, String tier, String planCode) {
        List<Map<String, Object>> rows = jdbc
                .queryForList("SELECT id, client_id, client_settings FROM oauth2_registered_client "
                        + "WHERE client_settings::jsonb->>'nexadrop.owner_user_id' = ?", userId.toString());
        int count = 0;
        List<String> clientIds = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            try {
                String settingsJson = (String) r.get("client_settings");
                Map<String, Object> settings = mapper.readValue(settingsJson, Map.class);
                settings.put("nexadrop.plan", tier);
                if (planCode != null)
                    settings.put("nexadrop.plan_code", planCode);
                settings.put("nexadrop.plan_synced_at", Instant.now().toString());
                jdbc.update("UPDATE oauth2_registered_client SET client_settings = ?::jsonb WHERE id = ?",
                        mapper.writeValueAsString(settings), r.get("id"));
                clientIds.add((String) r.get("client_id"));
                count++;
            } catch (Exception e) {
                log.error("Failed updating client_settings for {}: {}", r.get("id"), e.getMessage());
            }
        }
        // Revocación inmediata: cualquier JWT emitido antes de este momento
        // queda invalidado para todos los clients del usuario.
        if (revocationService != null && !clientIds.isEmpty()) {
            revocationService.revokeAllForClients(clientIds);
        }
        return count;
    }
}
