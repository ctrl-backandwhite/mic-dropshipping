package com.nexaplatform.dropshipping.api.dto.in;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * One product row of a bulk JSON import. Friendly, flat shape (the heavy
 * {@code IngestProductRequest} is built server-side). The category is referenced
 * by slug; the supplier is optional (a default is picked when omitted).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BulkProductDtoIn {

    /** Category slug the product belongs to (must already exist). */
    @NotBlank
    private String categorySlug;

    @NotBlank
    private String titleEs;

    private String titleEn;

    private String titleZh;

    private String descriptionEs;

    private BigDecimal price;

    private Integer moq;

    private Integer monthlySales;

    private BigDecimal rating;

    /** Optional supplier external id (1688); first available supplier used when null. */
    private String supplierExternalId;

    /** Optional product manufacturer (stored in the product's brand field). */
    private String manufacturer;

    private List<String> imageUrls;

    /** ACTIVE (default) or DRAFT. */
    private String status;

    /** Optional stable id; generated from the title when omitted. */
    private String externalId;
}
