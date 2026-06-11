package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.out.AdminOAuthClientDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminPartnerAppDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminPartnerWebhookDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminShopConnectionDtoOut;
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
 * Use-case service backing the Admin Partners endpoints. Encapsulates the
 * low-level JDBC access that previously lived in the controller and maps each
 * row into a typed DtoOut, preserving the exact JSON contract (column labels).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartnerAdminService {

    private final JdbcTemplate jdbc;

    /** Lists OAuth2 registered clients (admin-spa, storefront-spa, demo-partner, ...). */
    @Transactional(readOnly = true)
    public List<AdminOAuthClientDtoOut> listOAuthClients() {
        return jdbc.query(
                "SELECT id, client_id, client_name, client_authentication_methods AS auth_methods, " +
                        "authorization_grant_types AS grant_types, redirect_uris, scopes " +
                        "FROM oauth2_registered_client ORDER BY client_id",
                (rs, rowNum) -> AdminOAuthClientDtoOut.builder()
                        .id(rs.getString("id"))
                        .clientId(rs.getString("client_id"))
                        .clientName(rs.getString("client_name"))
                        .authMethods(rs.getString("auth_methods"))
                        .grantTypes(rs.getString("grant_types"))
                        .redirectUris(rs.getString("redirect_uris"))
                        .scopes(rs.getString("scopes"))
                        .build());
    }

    /** Recent webhook deliveries. */
    @Transactional(readOnly = true)
    public List<AdminPartnerWebhookDtoOut> listWebhooks() {
        return jdbc.query(
                "SELECT id, partner_app_id, event_type, status, attempt_count, response_code, created_at " +
                        "FROM partner_webhook_delivery ORDER BY created_at DESC LIMIT 50",
                (rs, rowNum) -> AdminPartnerWebhookDtoOut.builder()
                        .id(rs.getObject("id"))
                        .partnerAppId(rs.getObject("partner_app_id"))
                        .eventType(rs.getString("event_type"))
                        .status(rs.getString("status"))
                        .attemptCount(getInteger(rs, "attempt_count"))
                        .responseCode(getInteger(rs, "response_code"))
                        .createdAt(getInstant(rs, "created_at"))
                        .build());
    }

    @Transactional(readOnly = true)
    public List<AdminPartnerAppDtoOut> listPartnerApps() {
        return jdbc.query(
                "SELECT id, name, description, client_id, scopes, webhook_url, active, created_at " +
                        "FROM partner_app ORDER BY created_at DESC",
                (rs, rowNum) -> AdminPartnerAppDtoOut.builder()
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

    @Transactional(readOnly = true)
    public List<AdminShopConnectionDtoOut> listShopConnections() {
        return jdbc.query(
                "SELECT id, partner_app_id, platform, shop_handle, active, created_at " +
                        "FROM shop_connection ORDER BY created_at DESC",
                (rs, rowNum) -> AdminShopConnectionDtoOut.builder()
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
