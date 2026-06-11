package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.nexaplatform.dropshipping.api.dto.out.SearchHitDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SearchResultDtoOut;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSearchService {

    private final OpenSearchClient client;

    @Value("${nexadrop.opensearch.products-index}")
    private String index;

    public Map<String, Object> search(String keyword, String language, int page, int size) {
        String field = "title" + capitalize(language == null ? "es" : language);
        try {
            SearchResponse<Map> response = client.search(SearchRequest.of(s -> s
                    .index(index)
                    .from(page * size)
                    .size(size)
                    .query(keyword == null || keyword.isBlank()
                            ? Query.of(q -> q.matchAll(m -> m))
                            : Query.of(q -> q.multiMatch(m -> m.query(keyword).fields(field, "titleZh", "titleEn"))))
                    .sort(srt -> srt.field(f -> f.field("trendScore").order(SortOrder.Desc)))), Map.class);

            List<Map<String, Object>> hits = response.hits().hits().stream()
                    .map(h -> {
                        Map<String, Object> doc = new HashMap<>(h.source());
                        doc.put("_id", h.id());
                        doc.put("_score", h.score());
                        return doc;
                    }).toList();

            Map<String, Object> out = new HashMap<>();
            out.put("items", hits);
            out.put("total", response.hits().total() != null ? response.hits().total().value() : (long) hits.size());
            out.put("page", page);
            out.put("size", size);
            return out;
        } catch (IOException e) {
            log.error("Search failed: {}", e.getMessage());
            return Map.of("items", List.of(), "total", 0L, "page", page, "size", size);
        }
    }

    /**
     * Typed variant of {@link #search(String, String, int, int)} returning a
     * strongly-typed envelope instead of an ad-hoc {@code Map<String,Object>}.
     * Preserves the identical JSON contract ({@code items, total, page, size};
     * each hit flattens its source document plus {@code _id}/{@code _score}).
     */
    public SearchResultDtoOut searchTyped(String keyword, String language, int page, int size) {
        // Cap the page size to protect the search backend (was enforced in the controller).
        size = Math.min(size, 100);
        final int pageSize = size;
        final int fromOffset = page * size;
        String field = "title" + capitalize(language == null ? "es" : language);
        try {
            SearchResponse<Map> response = client.search(SearchRequest.of(s -> s
                    .index(index)
                    .from(fromOffset)
                    .size(pageSize)
                    .query(keyword == null || keyword.isBlank()
                            ? Query.of(q -> q.matchAll(m -> m))
                            : Query.of(q -> q.multiMatch(m -> m.query(keyword).fields(field, "titleZh", "titleEn"))))
                    .sort(srt -> srt.field(f -> f.field("trendScore").order(SortOrder.Desc)))), Map.class);

            List<SearchHitDtoOut> hits = response.hits().hits().stream()
                    .map(h -> {
                        Map<String, Object> doc = new HashMap<>(h.source());
                        doc.put("_id", h.id());
                        doc.put("_score", h.score());
                        return SearchHitDtoOut.builder().source(doc).build();
                    }).toList();

            long total = response.hits().total() != null ? response.hits().total().value() : (long) hits.size();
            return SearchResultDtoOut.builder()
                    .items(hits)
                    .total(total)
                    .page(page)
                    .size(size)
                    .build();
        } catch (IOException e) {
            log.error("Search failed: {}", e.getMessage());
            return SearchResultDtoOut.builder()
                    .items(List.of())
                    .total(0L)
                    .page(page)
                    .size(size)
                    .build();
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
