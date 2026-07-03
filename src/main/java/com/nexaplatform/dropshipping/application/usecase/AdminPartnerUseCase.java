package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.domain.model.AdminOAuthClient;
import com.nexaplatform.dropshipping.domain.model.AdminOAuthClientCreated;
import com.nexaplatform.dropshipping.domain.model.AdminPartnerApp;
import com.nexaplatform.dropshipping.domain.model.AdminShopConnection;
import com.nexaplatform.dropshipping.domain.model.AdminPartnerWebhook;

import java.util.List;

/**
 * Use-case port for the Admin Partners read projections. Pure reads: each method
 * aggregates a partner-related table into a list of domain projection models.
 * The api mapper turns those models into DtoOuts in the controller.
 */
public interface AdminPartnerUseCase {

    /** Lists OAuth2 registered clients (admin-spa, storefront-spa, demo-partner, ...). */
    List<AdminOAuthClient> listOAuthClients();

    /** Lists recent partner webhook deliveries. */
    List<AdminPartnerWebhook> listWebhooks();

    /** Lists registered partner apps. */
    List<AdminPartnerApp> listPartnerApps();

    /** Lists shop connections. */
    List<AdminShopConnection> listShopConnections();

    /** Creates an OAuth2 client (client_credentials) + su partner_app (owner); returns the plaintext secret once. */
    AdminOAuthClientCreated createOAuthClient(String name, List<String> scopes, java.util.UUID ownerUserId);

    /** Rotates an OAuth2 client's secret; returns the new plaintext secret once. */
    AdminOAuthClientCreated rotateSecret(String clientId);

    /** Deletes an OAuth2 client by id or client_id. */
    void deleteOAuthClient(String id);
}
