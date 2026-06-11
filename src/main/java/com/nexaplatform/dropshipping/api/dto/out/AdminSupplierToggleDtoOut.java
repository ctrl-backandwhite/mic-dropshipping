package com.nexaplatform.dropshipping.api.dto.out;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.util.UUID;

/**
 * Result of toggling a supplier flag (verified / trustPass). Field names preserve
 * the exact JSON keys previously emitted by the controller's ad-hoc {@code Map.of(...)}.
 * Null flags are omitted ({@code NON_NULL}) so each endpoint serializes exactly the
 * two keys it returned before: {@code {id, verified}} or {@code {id, trustPass}}.
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminSupplierToggleDtoOut {

    UUID id;
    Boolean verified;
    Boolean trustPass;
}
