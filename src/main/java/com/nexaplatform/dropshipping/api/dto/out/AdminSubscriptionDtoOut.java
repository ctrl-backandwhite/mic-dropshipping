package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin view of a customer subscription. Built from the JPA entity by MapStruct.
 */
@Value
@Builder
public class AdminSubscriptionDtoOut {

    UUID id;
    UUID userId;
    String userEmail;
    String plan;
    String status;
    String billingPeriod;
    Instant currentPeriodStart;
    Instant currentPeriodEnd;
    int priceMonthly;
    int priceYearly;
}
