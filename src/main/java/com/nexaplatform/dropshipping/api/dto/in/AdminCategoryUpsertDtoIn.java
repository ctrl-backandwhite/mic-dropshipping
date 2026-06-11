package com.nexaplatform.dropshipping.api.dto.in;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.UUID;

/**
 * Update payload for a catalog category — slug, names, parent, position, icon.
 * Preserves the JSON shape the frontend already sends.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminCategoryUpsertDtoIn {

    @NotBlank
    @Pattern(regexp = "^[a-z0-9][a-z0-9-]*[a-z0-9]$")
    private String slug;

    @NotBlank
    private String nameZh;

    private String icon;

    private Integer position;

    private Boolean active;

    private UUID parentId;

    private Map<String, String> names;
}
