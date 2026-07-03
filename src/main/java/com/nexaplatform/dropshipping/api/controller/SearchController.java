package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.SearchApi;
import com.nexaplatform.dropshipping.api.dto.out.SearchResultDtoOut;
import com.nexaplatform.dropshipping.infrastructure.integration.search.ProductSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController implements SearchApi {

    private final ProductSearchService searchService;

    @Override
    public ResponseEntity<SearchResultDtoOut> search(String q, String lang, int page, int size) {
        return ResponseEntity.ok(searchService.searchTyped(q, lang, page, size));
    }
}
