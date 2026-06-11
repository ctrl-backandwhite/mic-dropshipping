package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

/**
 * A single line item inside the admin order detail. Field names mirror the keys
 * the controller previously placed into its per-line {@code Map<String,Object>}.
 */
@Value
@Builder
public class AdminOrderLineDtoOut {

    UUID id;
    String sku;
    String title;
    int qty;
    int unitPriceCents;
    int lineTotalCents;
}
