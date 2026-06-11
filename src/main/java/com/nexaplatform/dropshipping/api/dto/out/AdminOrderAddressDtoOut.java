package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

/**
 * Shipping address block of the admin order detail. Field names mirror the keys
 * the controller previously placed into its address {@code Map<String,Object>}
 * (note: {@code region} maps from the entity {@code state} field).
 */
@Value
@Builder
public class AdminOrderAddressDtoOut {

    String fullName;
    String line1;
    String line2;
    String city;
    String region;
    String postalCode;
    String country;
    String phone;
}
