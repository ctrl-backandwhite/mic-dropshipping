package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Nested sub-entity of {@link Product}: a variant axis (e.g. "Color") with its values. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VariantOption {

    private UUID id;
    private String nameZh;
    private String name;
    private int position;
    @Builder.Default
    private List<VariantValue> values = new ArrayList<>();
}
