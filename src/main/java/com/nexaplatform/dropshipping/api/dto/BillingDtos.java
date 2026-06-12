package com.nexaplatform.dropshipping.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class BillingDtos {
    private BillingDtos() {
    }

    public record PlanView(UUID id, String code, String name, String description, int priceMonthlyCents,
            int priceYearlyCents, String currency, int position, Map<String, Long> limits) {
    }

    public record SubscribeRequest(@NotBlank String planCode, @NotBlank String period) {
    }

    public record SubscribeResponse(String checkoutUrl, String sessionId) {
    }

    public record SubscriptionView(UUID id, UUID planId, String planCode, String status, String billingPeriod,
            Instant currentPeriodStart, Instant currentPeriodEnd, Instant cancelAt, Instant trialEndsAt) {
    }
}
