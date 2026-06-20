package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CustomerSubscriptionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

import java.time.Duration;
import java.util.UUID;

@Configuration
public class RegisteredClientConfig {

    @Value("${nexadrop.oauth.partner-api.default-secret}")
    private String partnerSecret;

    private final PasswordEncoder passwordEncoder;
    private final RegisteredClientRepository repo;

    public RegisteredClientConfig(PasswordEncoder passwordEncoder, RegisteredClientRepository repo) {
        this.passwordEncoder = passwordEncoder;
        this.repo = repo;
    }

    @Bean
    public static RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbc) {
        return new JdbcRegisteredClientRepository(jdbc);
    }

    @Bean
    public static OAuth2AuthorizationService authorizationService(JdbcTemplate jdbc, RegisteredClientRepository repo) {
        return new JdbcOAuth2AuthorizationService(jdbc, repo);
    }

    @Bean
    public static OAuth2AuthorizationConsentService authorizationConsentService(JdbcTemplate jdbc,
            RegisteredClientRepository repo) {
        return new JdbcOAuth2AuthorizationConsentService(jdbc, repo);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedClients(ApplicationReadyEvent event) {
        // NOTA: los antiguos clientes SPA admin/storefront (authorization_code + PKCE) se
        // eliminaron al migrar el login del SPA a token Bearer propio (/api/auth/login).
        // El authorization server solo conserva el flujo partner (client_credentials).

        // Free / sandbox tier — 1 req/min. UPSERT: re-aplicamos settings y TTL si ya existe.
        upsertPartnerClient("demo-partner", "Demo Partner — Sandbox / Free (server-to-server)", partnerSecret,
                "sandbox");

        // Paid tier — 5 req/min.
        upsertPartnerClient("demo-partner-paid", "Demo Partner — Paid (server-to-server)", partnerSecret + "-paid",
                "paid");
    }

    /** Crea o actualiza el RegisteredClient. Idempotente — corrige TTL/plan en reinicio. */
    private void upsertPartnerClient(String clientId, String clientName, String rawSecret, String plan) {
        var existing = repo.findByClientId(clientId);
        String internalId = existing != null ? existing.getId() : UUID.randomUUID().toString();
        repo.save(RegisteredClient.withId(internalId).clientId(clientId).clientName(clientName)
                .clientSecret(passwordEncoder.encode(rawSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS).scope("catalog.read")
                .scope("orders.write").scope("shop.sync")
                .clientSettings(ClientSettings.builder().setting("nexadrop.plan", plan).build())
                .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofHours(12)).build()).build());
    }

    /**
     * Añade los claims `plan`, `owner_user_id` y `plan_code` al JWT del partner.
     *
     * Orden de resolución del plan (primer match gana):
     *   1. Setting explícito `nexadrop.plan` en RegisteredClient (override manual)
     *   2. Suscripción ACTIVE/TRIALING del owner_user_id linkeado (vía setting
     *      `nexadrop.owner_user_id`) → mapeo de plan.code a tier
     *   3. "sandbox" por defecto
     *
     * Mapeo plan.code → tier:
     *   FREE → sandbox
     *   STARTER, PRO, ENTERPRISE → paid
     *
     * El RateLimitFilter consume `plan` para aplicar 1/min (sandbox) o 5/min (paid).
     */
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> partnerPlanClaimCustomizer(
            CustomerSubscriptionRepository subsRepo) {
        return context -> {
            if (!"access_token".equals(context.getTokenType().getValue()))
                return;
            var settings = context.getRegisteredClient().getClientSettings();

            String tier = "sandbox";
            String planCode = null;
            UUID ownerUserId = null;

            // (1) Override explícito
            Object explicit = settings.getSetting("nexadrop.plan");
            if (explicit != null && !explicit.toString().isBlank()) {
                tier = explicit.toString();
                planCode = "OVERRIDE";
            } else {
                // (2) Resolver desde suscripción del owner
                Object owner = settings.getSetting("nexadrop.owner_user_id");
                if (owner != null) {
                    try {
                        ownerUserId = UUID.fromString(owner.toString());
                        var active = subsRepo.findActiveByUserId(ownerUserId);
                        if (!active.isEmpty()) {
                            var sub = active.get(0);
                            planCode = sub.getPlan().getCode();
                            tier = mapPlanCodeToTier(planCode);
                        }
                    } catch (IllegalArgumentException ignored) {
                        /* UUID malformado, sandbox */ }
                }
            }

            context.getClaims().claim("plan", tier);
            if (planCode != null)
                context.getClaims().claim("plan_code", planCode);
            if (ownerUserId != null)
                context.getClaims().claim("owner_user_id", ownerUserId.toString());
        };
    }

    private static String mapPlanCodeToTier(String planCode) {
        if (planCode == null)
            return "sandbox";
        return switch (planCode.toUpperCase()) {
            case "FREE" -> "sandbox";
            case "STARTER", "PRO", "ENTERPRISE" -> "paid";
            default -> "sandbox";
        };
    }

    @Bean
    public ObjectMapper authServerObjectMapper() {
        return new ObjectMapper();
    }
}
