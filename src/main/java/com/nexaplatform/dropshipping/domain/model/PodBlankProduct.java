package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read-only domain model for a POD blank-product card. Built by the query use
 * case from the catalog (translation-resolved title and main image), mapped to
 * the transport {@code PodBlankProductDtoOut}.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PodBlankProduct {

    private UUID id;
    private String slug;
    private String title;
    private String mainImage;
    private BigDecimal price;
}
