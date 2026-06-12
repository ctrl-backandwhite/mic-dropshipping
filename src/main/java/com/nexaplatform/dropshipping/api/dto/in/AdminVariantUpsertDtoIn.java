package com.nexaplatform.dropshipping.api.dto.in;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Create/update payload for a product variant (SKU, price, stock, options…),
 * managed from the admin product detail screen.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminVariantUpsertDtoIn {

    @NotBlank
    private String sku;

    private String title;

    @PositiveOrZero
    private BigDecimal price;

    @PositiveOrZero
    private Integer stock;

    private String barcode;

    private String imageUrl;

    /** Option name -> value (e.g. {"Color":"Negro","Talla":"M"}). */
    private Map<String, String> options;

    private Boolean active;
}
