package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Admin view of a supplier including derived performance KPIs. Field names
 * preserve the exact JSON keys previously emitted by the controller's ad-hoc
 * {@code Map<String,Object>}.
 */
@Value
@Builder
public class AdminSupplierDtoOut {

    UUID id;
    String externalId;
    String source;
    String name;
    String nameZh;
    String country;
    String city;
    BigDecimal rating;
    Integer yearsActive;
    boolean verified;
    boolean trustPass;
    String profileUrl;
    long productCount;
    long onTimePct;
    double defectRate;
    int responseHours;
    int leadTimeDays;
}
