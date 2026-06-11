package com.nexaplatform.dropshipping.api.dto.in;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Create payload for a webhook subscription.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WebhookSubscriptionCreateDtoIn {

    @NotBlank
    private String name;

    @NotBlank
    private String targetUrl;

    private List<String> events;

    private String description;
}
