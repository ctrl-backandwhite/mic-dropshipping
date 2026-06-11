package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.List;

/**
 * Pure domain projection for a self-service partner OAuth2 API key.
 *
 * <p>This single model backs both controller responses:
 * <ul>
 *   <li>the one-time creation view — carries the plaintext {@code clientSecret}
 *       (exposed exactly once, never persisted in clear) plus a {@code message};</li>
 *   <li>the listed view — {@code clientSecret} and {@code message} are {@code null}
 *       and the persisted {@code plan} claim is populated.</li>
 * </ul>
 *
 * Use cases operate on this model; the api mapper translates it to/from the
 * transport DTOs. It is not a JPA aggregate: the use case persists through the
 * OAuth registered-client store, so no domain repository port exists for it.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiKey {

    private String clientId;
    /** Plaintext secret — present only on the one-time creation projection. */
    private String clientSecret;
    private String name;
    private List<String> scopes;
    private Instant createdAt;
    /** Active subscription plan claim — present only on the listed projection. */
    private String plan;
    /** Human-readable hint — present only on the one-time creation projection. */
    private String message;
}
