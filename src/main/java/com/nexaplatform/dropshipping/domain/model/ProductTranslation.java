package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.UUID;

/** Nested sub-entity of {@link Product}: a per-language content translation. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductTranslation {

    private UUID id;
    private String language;
    private String title;
    private String shortDescription;
    private String description;
    private String metaTitle;
    private String metaDescription;
    private String provider;
}
