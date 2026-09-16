package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.nexaplatform.dropshipping.api.dto.out.SearchHitDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SearchResultDtoOut;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.TextQueryType;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_SEARCH;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Motor de búsqueda de texto libre del catálogo (OpenSearch).
 *
 * <p><b>Qué resuelve aquí y qué no.</b> Este servicio responde a UNA pregunta: <em>qué productos casan con
 * lo que ha escrito el usuario, y en qué orden de relevancia</em>. Devuelve solo identificadores. Los
 * filtros del escaparate (categoría, precio en la divisa activa, promoción…), la visibilidad y el precio
 * final los sigue resolviendo la base de datos, que es la fuente de la verdad. Así el buscador aporta lo
 * que sabe hacer — relevancia y morfología en 8 idiomas — sin duplicar reglas de negocio en el índice ni
 * obligar a reindexar cada vez que cambia un margen.
 *
 * <p><b>Dos pasadas, en este orden.</b>
 * <ol>
 *   <li><b>Estricta</b>: título (en el idioma del usuario y en los demás), chino, atributos, variantes y
 *       categoría. Sin descripciones y sin tolerancia a erratas.</li>
 *   <li><b>Amplia</b>, solo si la estricta no devuelve NADA: entran las descripciones y la tolerancia a
 *       erratas ({@code fuzziness}). Es la red de seguridad, no el caso normal.</li>
 * </ol>
 * El motivo es lo que se medía antes en producción: buscar "botas" devolvía faldas y vestidos cuya
 * descripción decía "combina con botas", y blazers cuyo título portugués ("botao") se parecía lo bastante
 * al término español. Nada de eso casa ya en la primera pasada, y la segunda solo entra cuando la
 * alternativa sería una página vacía.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSearchService {

    /** Tope de identificadores que se piden al índice para una búsqueda del escaparate. */
    public static final int MAX_IDS = 1000;

    /**
     * Idiomas con campo propio en el índice; para cualquier otro se usa el genérico multilingüe.
     *
     * <p>El chino ESTÁ en la lista: {@code titleZh} existe y se analiza con {@code cjk}. Al faltar,
     * {@code normalizeLang} lo degradaba a español y una búsqueda escrita en chino se lanzaba contra
     * {@code titleEs}, cuyo analizador parte los ideogramas de cualquier manera — de ahí que teclear el
     * nombre completo de un producto en chino no devolviera ese producto en ninguno de los casos medidos.
     */
    private static final Set<String> INDEXED_LANGS = Set.of("es", "en", "pt", "fr", "it", "de", "nl", "zh");

    /**
     * Campos del documento indexado que pueden salir por el buscador público. Lo que no esté aquí se
     * descarta.
     *
     * <p>Es una lista BLANCA a propósito, no una lista de exclusiones: el documento se arma en
     * {@code ProductIndexer} y crece cuando hace falta para buscar mejor, así que cualquier campo nuevo
     * saldría por aquí sin que nadie lo decidiera. Lo que se estaba escapando: {@code basePrice} —lo que
     * le pagamos al proveedor en CNY, y con el precio de venta delante una división da la ganancia—,
     * {@code externalId} y {@code source} —la oferta exacta de origen—, {@code supplierId} y
     * {@code titleZh} —el título del proveedor, que sirve para encontrarla—. El endpoint solo exige tener
     * cuenta, y una de prueba gratuita basta para paginar el catálogo entero. La ficha y el listado ya lo
     * tapaban ({@code ProductMapper}); esto cierra la misma puerta en el buscador.
     *
     * <p>Los campos que solo sirven para CASAR ({@code descAll}, {@code attrs}, {@code variants}) tampoco
     * salen: el escaparate no los pinta y el motor los usa dentro del índice, no en la respuesta.
     */
    private static final Set<String> CAMPOS_PUBLICOS = Set.of("id", "slug", "status", "mainImage",
            "categoryId", "categoryName", "rating", "monthlySales", "trendScore", "inventoryCount",
            "shipFrom", "hasVideo", "hasImage", "freeShipping", "selfPickup", "verified",
            "titleEs", "titleEn", "titlePt", "titleFr", "titleIt", "titleDe", "titleNl");

    private final OpenSearchClient client;
    private final ProductIndexer indexer;

    /**
     * Identificadores de producto que casan con el término, del más relevante al menos.
     *
     * @return vacío ({@link Optional#empty()}) si el buscador NO ha podido responder — índice caído, aún sin
     *         construir o error de red. Quien llama debe entonces caer al fallback SQL: un buscador caído
     *         degrada la búsqueda, nunca impide vender. Una lista vacía, en cambio, significa "he buscado y
     *         no hay nada", que es una respuesta legítima.
     */
    public Optional<List<UUID>> searchRelevantIds(String needle, String language) {
        if (needle == null || needle.isBlank()) {
            return Optional.empty();
        }
        String lang = normalizeLang(language);
        Optional<List<UUID>> strict = executeIds(needle, lang, false);
        if (strict.isEmpty() || !strict.get().isEmpty()) {
            return strict;
        }
        // Nada por título/atributo/variante: se amplía a descripciones y erratas antes de rendirse.
        return executeIds(needle, lang, true);
    }

    private Optional<List<UUID>> executeIds(String needle, String lang, boolean wide) {
        try {
            SearchResponse<Map> response = client.search(SearchRequest.of(s -> s.index(indexer.indexName())
                    .from(0).size(MAX_IDS)
                    .source(src -> src.fetch(false))
                    .query(visibleAndMatching(needle, lang, wide))
                    .sort(srt -> srt.score(sc -> sc.order(SortOrder.Desc)))
                    .sort(srt -> srt.field(f -> f.field("trendScore").order(SortOrder.Desc)))), Map.class);
            return Optional.of(response.hits().hits().stream().map(h -> UUID.fromString(h.id())).toList());
        } catch (Exception e) {
            // Cualquier fallo del buscador (caído, índice sin crear, timeout) se resuelve cayendo al SQL.
            log.warn("Búsqueda en OpenSearch no disponible ('{}'): {} — se usa el fallback SQL", needle,
                    e.getMessage());
            return Optional.empty();
        }
    }

    /** Solo productos publicados y con imagen espejada — mismo criterio de visibilidad que el SQL del escaparate. */
    private Query visibleAndMatching(String needle, String lang, boolean wide) {
        return Query.of(q -> q.bool(b -> b
                .must(matching(needle, lang, wide))
                .filter(f -> f.term(t -> t.field("status").value(FieldValue.of("ACTIVE"))))
                .filter(f -> f.term(t -> t.field("hasImage").value(FieldValue.of(true))))));
    }

    /**
     * El match de texto. Los pesos ordenan el resultado: el término en el título del idioma del usuario
     * manda sobre el mismo término en otro idioma, y ambos sobre un atributo o una variante.
     */
    private Query matching(String needle, String lang, boolean wide) {
        String titleField = "title" + capitalize(lang);
        return Query.of(q -> q.bool(b -> {
            // Frase exacta en el idioma del usuario: "botas de agua" gana a los que solo llevan "botas".
            b.should(s -> s.matchPhrase(m -> m.field(titleField).query(needle).boost(10f)));
            b.should(s -> s.match(m -> m.field(titleField).query(FieldValue.of(needle))
                    .minimumShouldMatch(MOST_TERMS).boost(8f)));
            // Los demás idiomas comparten un campo sin stemming: una coincidencia LITERAL entre idiomas es
            // intencional (un usuario en español buscando "blazer"), pero no debe competir con su idioma.
            b.should(s -> s.match(m -> m.field("titleAll").query(FieldValue.of(needle))
                    .minimumShouldMatch(MOST_TERMS).boost(3f)));
            // CON minimumShouldMatch, igual que el resto. Sin él esta cláusula era un OR puro: bastaba
            // que UNA palabra de la consulta apareciera en el campo para que el documento entrara con
            // peso 3. Como el analizador `cjk` no filtra palabras vacías, "de" casaba con miles de
            // productos y los colaba por delante del que se buscaba. Que un campo se llame "Zh" no
            // garantiza que su contenido sea chino, así que la cláusula tiene que defenderse sola.
            b.should(s -> s.match(m -> m.field("titleZh").query(FieldValue.of(needle))
                    .minimumShouldMatch(MOST_TERMS).boost(3f)));
            b.should(s -> s.match(m -> m.field("attrs").query(FieldValue.of(needle))
                    .minimumShouldMatch(MOST_TERMS).boost(2f)));
            b.should(s -> s.match(m -> m.field("variants").query(FieldValue.of(needle))
                    .minimumShouldMatch(MOST_TERMS).boost(1.5f)));
            b.should(s -> s.match(m -> m.field("categoryName").query(FieldValue.of(needle))
                    .minimumShouldMatch(MOST_TERMS).boost(1f)));
            if (wide) {
                b.should(s -> s.match(m -> m.field("descAll").query(FieldValue.of(needle))
                        .minimumShouldMatch(MOST_TERMS).boost(0.5f)));
                // Tolerancia a erratas SOLO en esta pasada: con ella activada siempre, "botas" arrastraba
                // vecinos a una edición de distancia ("bolas", "botao") que no son lo que se pide.
                b.should(s -> s.match(m -> m.field(titleField).query(FieldValue.of(needle))
                        .fuzziness("AUTO").boost(0.5f)));
            }
            // ANCLA AL TÍTULO — lo que hace coherente el resultado.
            //
            // Las cláusulas de arriba puntúan; esta decide quién ENTRA. Sin ella, un producto podía
            // aparecer casando solo por sus variantes o atributos, y eso devuelve cosas que no tienen nada
            // que ver: «vaqueros verde» traía un vestido cuyo único vínculo era tener un color «verde» y
            // otro «vaquero» — dos variantes distintas del mismo artículo, ningún vaquero por ninguna
            // parte. Para el comprador, «no hay vaqueros verdes» es mejor respuesta que un vestido.
            //
            // La regla es: las variantes y los atributos REFINAN la búsqueda, no la originan. Al menos un
            // término tiene que aparecer en algún título — el del idioma, el común o el chino—. Con eso
            // «vaqueros azul» sigue funcionando (el «vaquero» está en el título y el «azul» lo aporta la
            // variante), y el vestido de antes desaparece.
            b.must(anclaEnTitulo(needle, titleField));
            return b.minimumShouldMatch("1");
        }));
    }

    /**
     * Exige que la consulta toque el TÍTULO del producto, en cualquiera de sus formas.
     *
     * <p>Es un filtro de pertenencia, no de relevancia: basta UN término (así «vaqueros azul» entra por
     * «vaqueros» aunque el color viva en la variante), pero sin ninguno el producto no es un resultado,
     * por muchos atributos que casen.
     */
    private static Query anclaEnTitulo(String needle, String titleField) {
        return Query.of(q -> q.bool(b -> b
                .should(s -> s.match(m -> m.field(titleField).query(FieldValue.of(needle))))
                .should(s -> s.match(m -> m.field("titleAll").query(FieldValue.of(needle))))
                .should(s -> s.match(m -> m.field("titleZh").query(FieldValue.of(needle))))
                // La categoría cuenta como título a estos efectos: buscar «vestidos» debe traer lo que
                // vive en la categoría Vestido aunque su título diga «Prenda de tirantes».
                .should(s -> s.match(m -> m.field("categoryName").query(FieldValue.of(needle))))
                .minimumShouldMatch("1")));
    }

    /**
     * Búsqueda del endpoint público {@code /api/search}, con el documento completo.
     *
     * <p>Comparte query y pesos con {@link #searchRelevantIds}, así que ambos caminos ordenan igual. A
     * diferencia de aquél, ordena por relevancia real ({@code _score}) en lugar de por tendencia: antes
     * ordenaba por {@code trendScore} y el resultado más relevante podía quedar en la tercera página.
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
        String lang = normalizeLang(language);
        boolean blank = keyword == null || keyword.isBlank();
        try {
            SearchResponse<Map> response = client.search(SearchRequest.of(s -> s.index(indexer.indexName())
                    .from(fromOffset).size(pageSize)
                    .query(blank ? visibleAll() : visibleAndMatching(keyword, lang, false))
                    .sort(srt -> srt.score(sc -> sc.order(SortOrder.Desc)))
                    .sort(srt -> srt.field(f -> f.field("trendScore").order(SortOrder.Desc)))), Map.class);

            List<SearchHitDtoOut> hits = response.hits().hits().stream().map(h -> {
                Map<String, Object> doc = soloCamposPublicos(h.source());
                doc.put("_id", h.id());
                doc.put("_score", h.score());
                return SearchHitDtoOut.builder().source(doc).build();
            }).toList();

            long total = response.hits().total() != null ? response.hits().total().value() : (long) hits.size();
            return SearchResultDtoOut.builder().items(hits).total(total).page(page).size(size).build();
        } catch (Exception e) {
            log.error("Search failed: {}", e.getMessage());
            return SearchResultDtoOut.builder().items(List.of()).total(0L).page(page).size(size).build();
        }
    }

    /** Copia del documento indexado con solo los campos de {@link #CAMPOS_PUBLICOS}. */
    private static Map<String, Object> soloCamposPublicos(Map<?, ?> fuente) {
        Map<String, Object> publico = new HashMap<>();
        if (fuente == null) {
            return publico;
        }
        for (Map.Entry<?, ?> campo : fuente.entrySet()) {
            String clave = String.valueOf(campo.getKey());
            if (CAMPOS_PUBLICOS.contains(clave)) {
                publico.put(clave, campo.getValue());
            }
        }
        return publico;
    }

    /** Todo el catálogo visible (sin término de búsqueda). */
    private Query visibleAll() {
        return Query.of(q -> q.bool(b -> b
                .must(m -> m.matchAll(a -> a))
                .filter(f -> f.term(t -> t.field("status").value(FieldValue.of("ACTIVE"))))
                .filter(f -> f.term(t -> t.field("hasImage").value(FieldValue.of(true))))));
    }

    /**
     * Con uno o dos términos se exigen todos; a partir de tres basta el 75%. Escribir "botas de mujer
     * negras" no debe devolver cero por culpa de una palabra, pero "botas" tampoco debe traer todo lo de
     * "mujer".
     */
    private static final String MOST_TERMS = "2<75%";

    private static String normalizeLang(String language) {
        if (language == null || language.isBlank()) {
            return "es";
        }
        String lang = language.toLowerCase(Locale.ROOT);
        lang = lang.length() > 2 ? lang.substring(0, 2) : lang;
        return INDEXED_LANGS.contains(lang) ? lang : "es";
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty())
            return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
