package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.application.usecase.AdminPartnerUseCase;
import com.nexaplatform.dropshipping.domain.model.AdminOAuthClient;
import com.nexaplatform.dropshipping.domain.model.AdminPartnerApp;
import com.nexaplatform.dropshipping.domain.model.AdminShopConnection;
import com.nexaplatform.dropshipping.domain.model.AdminPartnerWebhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

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

    private final JdbcTemplate jdbc;

    @Override
    @Transactional(readOnly = true)
    public List<AdminOAuthClient> listOAuthClients() {
        return jdbc.query(
                "SELECT id, client_id, client_name, client_authentication_methods AS auth_methods, " +
                        "authorization_grant_types AS grant_types, redirect_uris, scopes " +
                        "FROM oauth2_registered_client ORDER BY client_id",
                (rs, rowNum) -> AdminOAuthClient.builder()
                        .id(rs.getString("id"))
                        .clientId(rs.getString("client_id"))
                        .clientName(rs.getString("client_name"))
                        .authMethods(rs.getString("auth_methods"))
                        .grantTypes(rs.getString("grant_types"))
                        .redirectUris(rs.getString("redirect_uris"))
                        .scopes(rs.getString("scopes"))
                        .build());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminPartnerWebhook> listWebhooks() {
        return jdbc.query(
                "SELECT id, partner_app_id, event_type, status, attempt_count, response_code, created_at " +
                        "FROM partner_webhook_delivery ORDER BY created_at DESC LIMIT 50",
                (rs, rowNum) -> AdminPartnerWebhook.builder()
                        .id(rs.getObject("id"))
                        .partnerAppId(rs.getObject("partner_app_id"))
                        .eventType(rs.getString("event_type"))
                        .status(rs.getString("status"))
                        .attemptCount(getInteger(rs, "attempt_count"))
                        .responseCode(getInteger(rs, "response_code"))
                        .createdAt(getInstant(rs, "created_at"))
                        .build());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminPartnerApp> listPartnerApps() {
        return jdbc.query(
                "SELECT id, name, description, client_id, scopes, webhook_url, active, created_at " +
                        "FROM partner_app ORDER BY created_at DESC",
                (rs, rowNum) -> AdminPartnerApp.builder()
                        .id(rs.getObject("id"))
                        .name(rs.getString("name"))
                        .description(rs.getString("description"))
                        .clientId(rs.getString("client_id"))
                        .scopes(rs.getString("scopes"))
                        .webhookUrl(rs.getString("webhook_url"))
                        .active(getBoolean(rs, "active"))
                        .createdAt(getInstant(rs, "created_at"))
                        .build());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminShopConnection> listShopConnections() {
        return jdbc.query(
                "SELECT id, partner_app_id, platform, shop_handle, active, created_at " +
                        "FROM shop_connection ORDER BY created_at DESC",
                (rs, rowNum) -> AdminShopConnection.builder()
                        .id(rs.getObject("id"))
                        .partnerAppId(rs.getObject("partner_app_id"))
                        .platform(rs.getString("platform"))
                        .shopHandle(rs.getString("shop_handle"))
                        .active(getBoolean(rs, "active"))
                        .createdAt(getInstant(rs, "created_at"))
                        .build());
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
