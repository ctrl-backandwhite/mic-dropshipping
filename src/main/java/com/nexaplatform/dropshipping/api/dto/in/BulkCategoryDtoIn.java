package com.nexaplatform.dropshipping.api.dto.in;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One category row of a bulk JSON import. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BulkCategoryDtoIn {

    @NotBlank
    private String slug;

    @NotBlank
    private String nameEs;

    private String nameEn;

    private String namePt;

    private String nameZh;

    private String icon;

    private Integer position;

    /** Optional slug of the parent category (for sub-categories). The parent must already exist
     *  or appear earlier in the same import batch; omit it for top-level categories. */
    private String parentSlug;
}
