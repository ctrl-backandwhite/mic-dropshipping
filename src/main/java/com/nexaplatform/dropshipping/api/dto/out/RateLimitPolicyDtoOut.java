package com.nexaplatform.dropshipping.api.dto.out;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.util.Map;

/**
 * Machine-readable rate-limit policy entry exposed at
 * {@code GET /api/v1/rate-limits}. Field names preserve the keys
 * previously emitted by the controller Map (name, path, scope, capacity,
 * period, tiers) so the public contract is unchanged. Null fields are omitted
 * so simple and plan-tiered policies keep their original JSON shape.
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RateLimitPolicyDtoOut {

    String name;
    String path;
    String scope;
    Integer capacity;
    String period;
    Map<String, Object> tiers;
}
