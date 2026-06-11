package com.nexaplatform.dropshipping.api.dto.out;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import lombok.Builder;
import lombok.Value;

import java.util.Map;

/**
 * A single search hit. The indexed document fields are dynamic (whatever the
 * products index stores), so they are flattened back into the JSON object via
 * {@link JsonAnyGetter} exactly as the previous {@code Map<String,Object>} did,
 * alongside the {@code _id} and {@code _score} metadata keys.
 */
@Value
@Builder
public class SearchHitDtoOut {

    /** Dynamic indexed document fields, flattened into the JSON object. */
    @JsonAnyGetter
    Map<String, Object> source;
}
