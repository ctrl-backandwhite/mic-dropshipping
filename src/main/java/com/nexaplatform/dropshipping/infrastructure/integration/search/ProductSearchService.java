package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.nexaplatform.dropshipping.api.dto.out.SearchHitDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SearchResultDtoOut;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_SEARCH;

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

    /**
     * Búsqueda de productos en el índice, con envoltorio tipado.
     *
     * <p>Aquí convivía un {@code search(...)} que devolvía un {@code Map<String,Object>} suelto y al que
     * ya no llamaba nadie. Estaba borrado y se ha ido con él: a diferencia de éste, NO acotaba page ni
     * size, así que reexponerlo habría reintroducido el 500 por parámetro inválido que se corrigió aquí.
     * El contrato JSON es el mismo ({@code items, total, page, size}; cada resultado con su documento
     * más {@code _id}/{@code _score}).
     */
    // Caché de resultados de búsqueda (TTL 60s): la búsqueda es el punto caliente de OpenSearch bajo carga
    // y las consultas populares se repiten. Clave = keyword+idioma+page+size. NO depende de la moneda (los
    // hits devuelven el documento indexado, sin precio convertido). Solo se cachean keywords no vacías.
    @Cacheable(value = CACHE_SEARCH, key = "#keyword + ':' + #language + ':' + #page + ':' + #size",
            condition = "#keyword != null && !#keyword.isBlank()")
    public SearchResultDtoOut searchTyped(String keyword, String language, int page, int size) {
        // Se acota el tamaño por arriba para proteger al motor de búsqueda y también POR ABAJO: un
        // `size` o `page` negativos llegaban tal cual a OpenSearch, que respondía con un error y salía
        // como 500. Un parámetro inválido no debe convertirse en un fallo del servidor ni dar a
        // cualquiera una forma trivial de provocarlos.
        final int pageSize = Math.clamp(size, 1, 100);
        final int fromOffset = Math.max(0, page) * pageSize;
        String field = "title" + capitalize(language == null ? "es" : language);
        try {
            SearchResponse<Map> response = client.search(SearchRequest.of(s -> s.index(index).from(fromOffset)
                    .size(pageSize)
                    .query(withImageFilter(keyword, field))
                    .sort(srt -> srt.field(f -> f.field("trendScore").order(SortOrder.Desc)))), Map.class);

            List<SearchHitDtoOut> hits = response.hits().hits().stream().map(h -> {
                Map<String, Object> doc = new HashMap<>(h.source());
                doc.put("_id", h.id());
                doc.put("_score", h.score());
                return SearchHitDtoOut.builder().source(doc).build();
            }).toList();

            long total = response.hits().total() != null ? response.hits().total().value() : (long) hits.size();
            return SearchResultDtoOut.builder().items(hits).total(total).page(page).size(size).build();
        } catch (IOException e) {
            log.error("Search failed: {}", e.getMessage());
            return SearchResultDtoOut.builder().items(List.of()).total(0L).page(page).size(size).build();
        }
    }

    /**
     * Construye la query de búsqueda (matchAll si no hay keyword; multiMatch si la hay) envuelta en un
     * filtro de imagen: solo se devuelven productos con {@code hasImage=true} (imagen espejada a nuestro
     * storage) — mismo criterio que el filtro SQL del escaparate. Es tolerante con los documentos viejos
     * que aún no tienen el campo {@code hasImage} (se incluyen hasta el siguiente reindexado), para no
     * vaciar la búsqueda durante la transición.
     */
    private Query withImageFilter(String keyword, String field) {
        Query content = (keyword == null || keyword.isBlank())
                ? Query.of(q -> q.matchAll(m -> m))
                : Query.of(q -> q.multiMatch(m -> m.query(keyword).fields(field, "titleZh", "titleEn")));
        return Query.of(q -> q.bool(b -> b
                .must(content)
                .filter(f -> f.bool(bb -> bb
                        .should(s1 -> s1.term(t -> t.field("hasImage").value(FieldValue.of(true))))
                        .should(s2 -> s2.bool(mn -> mn.mustNot(e -> e.exists(ex -> ex.field("hasImage")))))
                        .minimumShouldMatch("1")))));
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty())
            return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
