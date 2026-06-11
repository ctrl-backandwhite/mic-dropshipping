package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * View of a print-on-demand design. {@code canvasJson} is a free-form JSON blob
 * mirroring the entity's JSON column, so it stays a Map by design.
 */
@Value
@Builder
public class PodDesignDtoOut {

    UUID id;
    UUID productId;
    String productTitle;
    String name;
    Map<String, Object> canvasJson;
    String mockupUrl;
    String status;
    String aiPrompt;
    Instant createdAt;
}
