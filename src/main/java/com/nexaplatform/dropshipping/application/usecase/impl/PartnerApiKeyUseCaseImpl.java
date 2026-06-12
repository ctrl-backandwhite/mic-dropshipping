package com.nexaplatform.dropshipping.application.usecase.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.PartnerApiKeyUseCase;
import com.nexaplatform.dropshipping.domain.model.ApiKey;
import com.nexaplatform.dropshipping.infrastructure.security.oauth.JwtRevocationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Use case for self-service partner OAuth2 client_credentials.
 *
 * <p>Holds all the security/token logic that previously lived in the controller
 * and the obsolete {@code PartnerApiKeyService}: quota enforcement, scope
 * validation, client_id/secret generation, persistence via the
 * {@link RegisteredClientRepository} and immediate JWT revocation on delete.
 *
 * <p>This controller commands the OAuth registered-client store directly, so —
 * per the slice plan — no domain repository port is introduced; the existing
 * security collaborators are injected as read/write collaborators. Operations
 * return the {@link ApiKey} domain projection (never a DtoOut).
 */
@Slf4j
@Service
public class PartnerApiKeyUseCaseImpl implements PartnerApiKeyUseCase {

    private static final String OWNER_KEY = "nexadrop.owner_user_id";
    private static final String CREATED_KEY = "nexadrop.created_at";
    private static final String PLAN_KEY = "nexadrop.plan";
    private static final String CLIENT_ID_PREFIX = "pk_"; // "partner key"
    private static final Set<String> ALLOWED_SCOPES = Set.of("catalog.read", "orders.write", "shop.sync");
    private static final List<String> DEFAULT_SCOPES = List.of("catalog.read", "orders.write", "shop.sync");
    private static final int MAX_KEYS_PER_USER = 5;

    private final RegisteredClientRepository repo;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final JwtRevocationService revocationService;
    private final SecureRandom rng = new SecureRandom();

    public PartnerApiKeyUseCaseImpl(RegisteredClientRepository repo, PasswordEncoder passwordEncoder, JdbcTemplate jdbc,
            ObjectMapper mapper, JwtRevocationService revocationService) {
        this.repo = repo;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.revocationService = revocationService;
    }

    /**
     * Creates a fresh OAuth2 client linked to the user. The plaintext secret is
     * returned only on the resulting projection and never stored in clear.
     */
    @Override
    @Transactional
    public ApiKey create(UUID userId, ApiKey command) {
        // Limit: max 5 active API keys per user.
        long existing = countForUser(userId);
        if (existing >= MAX_KEYS_PER_USER) {
            throw new BusinessException("API key quota exceeded (5 per user). Revoke unused keys first.");
        }

        List<String> scopes = (command.getScopes() == null || command.getScopes().isEmpty())
                ? DEFAULT_SCOPES
                : command.getScopes();
        validateScopes(scopes);

        String clientId = CLIENT_ID_PREFIX + userId.toString().substring(0, 8) + "_" + randomToken(10);
        String clientSecret = "sk_" + randomToken(40);
        Instant now = Instant.now();

        ClientSettings.Builder settings = ClientSettings.builder().setting(OWNER_KEY, userId.toString())
                .setting(CREATED_KEY, now.toString());

        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString()).clientId(clientId)
                .clientName(command.getName()).clientSecret(passwordEncoder.encode(clientSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS).scopes(s -> s.addAll(scopes))
                .clientSettings(settings.build())
                .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofHours(12)).build()).build();
        repo.save(client);

        log.info("::> [PARTNER-KEYS] API key created clientId={} owner={}", clientId, userId);
        return ApiKey.builder().clientId(clientId).clientSecret(clientSecret).name(command.getName()).scopes(scopes)
                .createdAt(now).message("Store the clientSecret now — it will not be shown again.").build();
    }

    /** Lists the API keys owned by the user (no secrets). */
    @Override
    @Transactional(readOnly = true)
    public List<ApiKey> list(UUID userId) {
        return queryByOwner(userId);
    }

    /**
     * Revokes (deletes) an API key after verifying ownership and triggers
     * immediate revocation of any live JWT issued for it.
     */
    @Override
    @Transactional
    public void revoke(UUID userId, String clientId) {
        // Check ownership before delete.
        RegisteredClient client = repo.findByClientId(clientId);
        if (client == null) {
            throw new NotFoundException("API key");
        }
        Object owner = client.getClientSettings().getSetting(OWNER_KEY);
        if (owner == null || !userId.toString().equals(owner.toString())) {
            throw new NotFoundException("API key"); // 404 instead of 403 (no leak that it exists)
        }
        jdbc.update("DELETE FROM oauth2_registered_client WHERE client_id = ?", clientId);
        // Immediate revocation of any live JWT for this credential.
        revocationService.revokeAllForClient(clientId);
        log.info("::> [PARTNER-KEYS] API key revoked clientId={} owner={}", clientId, userId);
    }

    /* ============================== Helpers ============================== */

    /**
     * Counts the user's credentials. Looks up the JSON {@code client_settings}
     * column for the {@code nexadrop.owner_user_id} setting.
     */
    private long countForUser(UUID userId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM oauth2_registered_client WHERE client_settings::jsonb->>'nexadrop.owner_user_id' = ?",
                Long.class, userId.toString());
    }

    @SuppressWarnings("unchecked")
    private List<ApiKey> queryByOwner(UUID userId) {
        List<Map<String, Object>> rows = jdbc
                .queryForList("SELECT client_id, client_name, scopes, client_settings::text AS settings "
                        + "FROM oauth2_registered_client "
                        + "WHERE client_settings::jsonb->>'nexadrop.owner_user_id' = ? "
                        + "ORDER BY client_id_issued_at DESC", userId.toString());
        List<ApiKey> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> settings;
            try {
                settings = mapper.readValue((String) r.get("settings"), Map.class);
            } catch (Exception e) {
                settings = Map.of();
            }
            String createdAt = (String) settings.getOrDefault(CREATED_KEY, null);
            String plan = (String) settings.getOrDefault(PLAN_KEY, null);
            List<String> scopes = Arrays.asList(((String) r.get("scopes")).split(","));
            out.add(ApiKey.builder().clientId((String) r.get("client_id")).name((String) r.get("client_name"))
                    .scopes(scopes).createdAt(createdAt != null ? Instant.parse(createdAt) : null).plan(plan).build());
        }
        return out;
    }

    private void validateScopes(List<String> scopes) {
        for (String s : scopes) {
            if (!ALLOWED_SCOPES.contains(s)) {
                throw new BusinessException("Unknown scope: " + s);
            }
        }
    }

    private String randomToken(int chars) {
        byte[] raw = new byte[chars];
        rng.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw).substring(0, chars);
    }
}
