package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Storefront search result envelope. Field names preserve the exact JSON keys
 * previously emitted by the search service's ad-hoc {@code Map<String,Object>}:
 * {@code items}, {@code total}, {@code page}, {@code size}.
 */
@Value
@Builder
public class SearchResultDtoOut {

    List<SearchHitDtoOut> items;
    long total;
    int page;
    int size;
}
