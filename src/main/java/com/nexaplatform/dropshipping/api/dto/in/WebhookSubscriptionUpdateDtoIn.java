package com.nexaplatform.dropshipping.api.dto.in;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Partial-update payload for a webhook subscription. All fields are optional;
 * null fields are ignored by the MapStruct update mapper (no overwrite).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WebhookSubscriptionUpdateDtoIn {

    private String name;
    private String targetUrl;
    private String description;
    private Boolean active;
    private List<String> events;
}
