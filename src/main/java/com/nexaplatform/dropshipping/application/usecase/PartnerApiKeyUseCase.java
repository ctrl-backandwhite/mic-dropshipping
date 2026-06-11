package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.domain.model.ApiKey;

import java.util.List;
import java.util.UUID;

/**
 * Use-case port for self-service partner OAuth2 API keys.
 *
 * <p>This is a command/read facade over the OAuth registered-client store rather
 * than a JPA aggregate, so it does not extend the generic CRUD base. Every
 * operation is scoped to the authenticated user resolved by the controller and
 * operates on the {@link ApiKey} domain projection.
 */
public interface PartnerApiKeyUseCase {

    /**
     * Creates a fresh OAuth2 client for the user. The returned model carries the
     * plaintext secret exactly once (it is never stored in clear).
     *
     * @param userId  authenticated user owning the credential
     * @param command requested key (name + optional scopes)
     */
    ApiKey create(UUID userId, ApiKey command);

    /** Lists the API keys owned by the user (without secrets). */
    List<ApiKey> list(UUID userId);

    /**
     * Revokes (deletes) a key after verifying ownership and immediately revokes
     * any live JWT issued for it.
     */
    void revoke(UUID userId, String clientId);
}
