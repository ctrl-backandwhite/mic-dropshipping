package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.AdminPartnerUseCase;
import com.nexaplatform.dropshipping.domain.model.AdminOAuthClient;
import com.nexaplatform.dropshipping.domain.model.AdminOAuthClientCreated;
import com.nexaplatform.dropshipping.domain.model.AdminPartnerApp;
import com.nexaplatform.dropshipping.domain.model.AdminShopConnection;
import com.nexaplatform.dropshipping.domain.model.AdminPartnerWebhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Admin Partners read-projection use case. Encapsulates the low-level JDBC access
 * that previously lived in {@code PartnerAdminService} and maps each row into a
 * domain projection model. Pure reads: no domain port is required, the existing
 * {@link JdbcTemplate} is injected directly as the read collaborator.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminPartnerUseCaseImpl implements AdminPartnerUseCase {

    private static final SecureRandom RNG = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final RegisteredClientRepository registeredClientRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public AdminOAuthClientCreated createOAuthClient(String name, List<String> scopes) {
        if (name == null || name.isBlank()) {
            throw new BusinessException("El nombre del cliente es obligatorio");
        }
        // Scopes con PUNTO (catalog.read, orders.write, shop.sync) — así los exige el ResourceServer del
        // partner API y así los documentan los docs. Normalizamos ':' → '.' por si llegan con el separador
        // antiguo, para que el authority concedido (SCOPE_catalog.read) coincida con el requerido.
        List<String> sc = (scopes == null || scopes.isEmpty())
                ? List.of("catalog.read")
                : scopes.stream().map(s -> s.replace(':', '.')).toList();
        String clientId = "partner_" + token(8);
        String clientSecret = "sk_" + token(24);
        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString()).clientId(clientId)
                .clientName(name).clientSecret(passwordEncoder.encode(clientSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS).scopes(s -> s.addAll(sc))
                .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofHours(12)).build()).build();
        registeredClientRepository.save(client);
        log.info("::> [PARTNER] OAuth client created clientId={}", clientId);
        return AdminOAuthClientCreated.builder().id(client.getId()).clientId(clientId).clientSecret(clientSecret)
                .name(name).build();
    }

    @Override
    @Transactional
    public AdminOAuthClientCreated rotateSecret(String clientId) {
        RegisteredClient existing = registeredClientRepository.findByClientId(clientId);
        if (existing == null) {
            throw new NotFoundException("Cliente OAuth no encontrado");
        }
        String clientSecret = "sk_" + token(24);
        RegisteredClient rotated = RegisteredClient.from(existing).clientSecret(passwordEncoder.encode(clientSecret))
                .build();
        registeredClientRepository.save(rotated);
        log.info("::> [PARTNER] OAuth client secret rotated clientId={}", clientId);
        return AdminOAuthClientCreated.builder().id(existing.getId()).clientId(clientId).clientSecret(clientSecret)
                .name(existing.getClientName()).build();
    }

    @Override
    @Transactional
    public void deleteOAuthClient(String id) {
        int rows = jdbc.update("DELETE FROM oauth2_registered_client WHERE id = ? OR client_id = ?", id, id);
        if (rows == 0) {
            throw new NotFoundException("Cliente OAuth no encontrado");
        }
        log.info("::> [PARTNER] OAuth client deleted id={}", id);
    }

    private static String token(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminOAuthClient> listOAuthClients() {
        return jdbc.query(
                "SELECT id, client_id, client_name, client_authentication_methods AS auth_methods, "
                        + "authorization_grant_types AS grant_types, redirect_uris, scopes "
                        + "FROM oauth2_registered_client ORDER BY client_id",
                (rs, rowNum) -> AdminOAuthClient.builder().id(rs.getString("id")).clientId(rs.getString("client_id"))
                        .clientName(rs.getString("client_name")).authMethods(rs.getString("auth_methods"))
                        .grantTypes(rs.getString("grant_types")).redirectUris(rs.getString("redirect_uris"))
                        .scopes(rs.getString("scopes")).build());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminPartnerWebhook> listWebhooks() {
        return jdbc.query(
                "SELECT id, partner_app_id, event_type, status, attempt_count, response_code, created_at "
                        + "FROM partner_webhook_delivery ORDER BY created_at DESC LIMIT 50",
                (rs, rowNum) -> AdminPartnerWebhook.builder().id(rs.getObject("id"))
                        .partnerAppId(rs.getObject("partner_app_id")).eventType(rs.getString("event_type"))
                        .status(rs.getString("status")).attemptCount(getInteger(rs, "attempt_count"))
                        .responseCode(getInteger(rs, "response_code")).createdAt(getInstant(rs, "created_at")).build());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminPartnerApp> listPartnerApps() {
        return jdbc.query(
                "SELECT id, name, description, client_id, scopes, webhook_url, active, created_at "
                        + "FROM partner_app ORDER BY created_at DESC",
                (rs, rowNum) -> AdminPartnerApp.builder().id(rs.getObject("id")).name(rs.getString("name"))
                        .description(rs.getString("description")).clientId(rs.getString("client_id"))
                        .scopes(rs.getString("scopes")).webhookUrl(rs.getString("webhook_url"))
                        .active(getBoolean(rs, "active")).createdAt(getInstant(rs, "created_at")).build());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminShopConnection> listShopConnections() {
        return jdbc.query(
                "SELECT id, partner_app_id, platform, shop_handle, active, created_at "
                        + "FROM shop_connection ORDER BY created_at DESC",
                (rs, rowNum) -> AdminShopConnection.builder().id(rs.getObject("id"))
                        .partnerAppId(rs.getObject("partner_app_id")).platform(rs.getString("platform"))
                        .shopHandle(rs.getString("shop_handle")).active(getBoolean(rs, "active"))
                        .createdAt(getInstant(rs, "created_at")).build());
    }

    private static Integer getInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Boolean getBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant getInstant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts != null ? ts.toInstant() : null;
    }
}
