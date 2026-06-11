package com.nexaplatform.dropshipping.api.dto.in;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.UUID;

/**
 * Create-design payload. {@code canvasJson} is a free-form JSON blob persisted
 * verbatim into the entity's JSON column, so it stays a Map by design.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PodDesignCreateDtoIn {

    @NotNull
    private UUID productId;

    @NotBlank
    private String name;

    private Map<String, Object> canvasJson;

    private String aiPrompt;
}
