package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/**
 * Domain-level result of starting a subscription checkout: the URL the customer
 * must be redirected to and the provider session id (or the local subscription id
 * in Stripe-disabled dev mode). The API mapper projects it to {@code SubscribeDtoOut}.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscribeResult {

    private String checkoutUrl;
    private String sessionId;
}
