package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Lightweight POD blank-product card for the storefront catalog.
 * Field names mirror the keys previously emitted by the controller Map
 * (id, slug, title, mainImage, price) to preserve the JSON contract.
 */
@Value
@Builder
public class PodBlankProductDtoOut {

    UUID id;
    String slug;
    String title;
    String mainImage;
    BigDecimal price;
}
