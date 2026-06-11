package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.UUID;

/** Nested sub-entity of {@link VariantOption}: one selectable value (e.g. "Red"). */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VariantValue {

    private UUID id;
    private String valueZh;
    private String value;
    private String imageSourceUrl;
    private String imageCdnUrl;
    private int position;
}
