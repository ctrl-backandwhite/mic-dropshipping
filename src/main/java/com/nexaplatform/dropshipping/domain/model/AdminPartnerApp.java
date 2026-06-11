package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;

/**
 * Pure domain projection for a partner app row. Read-only model aggregated by
 * the Admin Partners use case from the {@code partner_app} table; the api mapper
 * translates it to {@code AdminPartnerAppDtoOut}.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPartnerApp {

    private Object id;
    private String name;
    private String description;
    private String clientId;
    private String scopes;
    private String webhookUrl;
    private Boolean active;
    private Instant createdAt;
}
