package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

/**
 * Public view of an active warehouse.
 */
@Value
@Builder
public class WarehouseDtoOut {

    UUID id;
    String code;
    String name;
    String country;
    String city;
    boolean active;
}
