package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

/**
 * View of an ODM/OEM project.
 */
@Value
@Builder
public class OdmProjectDtoOut {

    UUID id;
    String kind;
    String title;
    String brief;
    Integer budgetUsdCents;
    Integer slaDays;
    String status;
    Instant createdAt;
}
