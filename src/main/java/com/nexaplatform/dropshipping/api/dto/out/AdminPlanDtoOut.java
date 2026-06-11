package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

/**
 * Admin view of a subscription plan. Built from the JPA entity by MapStruct.
 */
@Value
@Builder
public class AdminPlanDtoOut {

    UUID id;
    String code;
    String name;
    String description;
    int priceMonthlyCents;
    int priceYearlyCents;
    String currency;
    boolean active;
    int position;
}
