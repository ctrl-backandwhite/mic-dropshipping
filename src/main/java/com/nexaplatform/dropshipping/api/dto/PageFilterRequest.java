package com.nexaplatform.dropshipping.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Canonical pagination/sort/filter request (mirrors the core's
 * {@code PageFilterRequest}). The {@code filters} payload is any DTO whose
 * non-null fields are used as equality predicates.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageFilterRequest<F> {

    @Min(0)
    @Builder.Default
    @Schema(description = "Page number (0-based)", example = "0")
    private int page = 0;

    @Min(1)
    @Max(200)
    @Builder.Default
    @Schema(description = "Page size (max 200)", example = "20")
    private int size = 20;

    @Builder.Default
    @Schema(description = "Sort field", example = "createdAt")
    private String sortBy = "createdAt";

    @Builder.Default
    @Schema(description = "Ascending order", example = "true")
    private boolean ascending = true;

    @Schema(description = "Language code for translation filtering (e.g. es, en, pt-BR). Null = no filter")
    private String locale;

    @Schema(description = "Dynamic filters: any non-null field is used as an equality predicate")
    private F filters;
}
