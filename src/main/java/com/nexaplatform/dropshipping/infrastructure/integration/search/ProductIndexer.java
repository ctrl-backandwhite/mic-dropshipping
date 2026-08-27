package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductAttributeEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.messaging.NexaTopics;
import com.nexaplatform.dropshipping.infrastructure.messaging.ProductIngestedEvent;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductAttributeRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductIndexer {

    /** Idiomas con analizador propio en el índice. El chino se indexa aparte, en {@code titleZh} (analizador cjk). */
    private static final Set<String> INDEXED_LANGS = Set.of("es", "en", "pt", "fr", "it", "de", "nl");

    private final OpenSearchClient client;
    private final ProductRepository productRepository;
    private final ProductAttributeRepository productAttributeRepository;
    private final ProductIndexSchema schema;

    @Value("${nexadrop.opensearch.products-index}")
    private String logicalIndex;

    /** Nombre físico (versionado) del índice — lo resuelve {@link ProductIndexSchema}. */
    private String index;

    /** true si el índice se acaba de crear en este arranque ⇒ está vacío y hay que reindexarlo. */
    @Getter
    private boolean freshlyCreated;

    @PostConstruct
    public void ensureIndex() {
        index = schema.indexName(logicalIndex);
        try {
            freshlyCreated = schema.createIfMissing(logicalIndex);
        } catch (RuntimeException | IOException e) {
            // RuntimeException y no sólo OpenSearchException: esto corre en @PostConstruct, así que
            // cualquier fallo del cliente que no fuera exactamente esa excepción (una URL mal formada, un
            // certificado, un timeout envuelto) abortaba el ARRANQUE de la aplicación entera. Un buscador
            // caído degrada la búsqueda; nunca debe impedir vender.
            log.error("Failed to ensure OpenSearch index: {}", e.getMessage());
        }
    }

    /** Nombre físico del índice de productos — lo consultan el servicio de búsqueda y el arranque. */
    public String indexName() {
        return index != null ? index : schema.indexName(logicalIndex);
    }

    @KafkaListener(topics = NexaTopics.PRODUCT_INGESTED, groupId = "nexadrop-search-indexer")
    // La transacción tiene que abrirse AQUÍ: el cuerpo del indexado se llama en la misma clase y una
    // llamada interna no pasa por el proxy de Spring, así que el indexado corría sin sesión JPA.
    @Transactional(readOnly = true)
    public void onProductIngested(ProductIngestedEvent event) {
        indexProductDoc(event.productId());
    }

    /** Removes a single product document from the search index (best-effort). */
    public void deleteFromIndex(UUID productId) {
        try {
            client.delete(d -> d.index(index).id(productId.toString()));
        } catch (Exception e) {
            log.warn("Index delete failed for product {}: {}", productId, e.getMessage());
        }
    }

    /**
     * Re-indexes every product into OpenSearch. {@code @Transactional} keeps one session open
     * for the whole sweep so the per-product indexing body can read the lazy
     * {@code translations}/{@code images} collections. Returns the number indexed.
     */
    @Transactional(readOnly = true)
    public int reindexAll() {
        // PURGA primero: borra los documentos obsoletos (productos ya eliminados de la BD) para que el
        // índice quede EXACTAMENTE igual que la BD. Sin esto, un producto borrado seguía apareciendo en
        // la búsqueda (documento huérfano con un UUID que ya no existe) porque el reindex solo hacía upsert.
        purgeIndex();
        int[] counters = { 0, 0 };
        productRepository.findAll().forEach(p -> {
            if (indexProductDoc(p.getId())) {
                counters[0]++;
            } else {
                counters[1]++;
            }
        });
        // Se cuenta lo que REALMENTE entró en el índice, no lo que se intentó: contando intentos, un fallo
        // de serialización dejaba el índice vacío mientras el log (y el admin) decían "5.450 productos".
        if (counters[1] > 0) {
            log.error("::> [REINDEX] {} productos NO se pudieron indexar en '{}' (ver errores arriba)", counters[1],
                    index);
        }
        log.info("::> [REINDEX] reindexed {} products into '{}' (índice purgado antes de reconstruir)", counters[0],
                index);
        return counters[0];
    }

    /** Elimina TODOS los documentos del índice de productos (match_all), conservando el índice y su mapping. */
    private void purgeIndex() {
        try {
            client.deleteByQuery(d -> d.index(index).query(q -> q.matchAll(m -> m)).refresh(true));
            log.info("::> [REINDEX] purged stale documents from '{}'", index);
        } catch (OpenSearchException | IOException e) {
            log.warn("Index purge failed for '{}': {} (se continúa con el upsert)", index, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public void indexProduct(UUID productId) {
        indexProductDoc(productId);
    }

    /**
     * Cuerpo del indexado, SIN anotar. Es al que llaman {@link #onProductIngested} y {@link #reindexAll},
     * que ya abren la sesión JPA: anotarlo de nuevo aquí no serviría de nada porque una llamada dentro de
     * la misma instancia no atraviesa el proxy de Spring.
     */
    private boolean indexProductDoc(UUID productId) {
        ProductEntity p = productRepository.findWithDetailsById(productId).orElse(null);
        if (p == null)
            return false;
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", p.getId().toString());
        doc.put("slug", p.getSlug());
        doc.put("source", p.getSource());
        doc.put("externalId", p.getExternalId());
        doc.put("status", p.getStatus() != null ? p.getStatus().name() : null);
        // SOLO si de verdad es chino. La columna `title_zh` es el título de ORIGEN y hace de reserva
        // cuando un producto no tiene traducción, así que en la práctica 4.791 de 5.659 referencias
        // activas llevan ahí el título en español. Indexarlo tal cual en un campo con analizador `cjk`
        // —que no conoce las palabras vacías españolas— convertía cada "de", "con" o "para" en ~4.300
        // coincidencias con peso 3, y esas coincidencias sepultaban al producto correcto: buscar
        // "Blazer de" devolvía 4.368 resultados y el blazer buscado caía al tercer puesto.
        // Las descripciones van TODAS a un único campo `descAll`. No entran en el match principal (una
        // falda cuya descripción dice "combina con botas" no es un resultado de "botas"); solo se rastrean
        // en el segundo intento, cuando el título/atributo/variante no ha encontrado nada.
        StringBuilder descriptions = new StringBuilder();
        String tituloChino = null;
        for (ProductTranslationEntity t : p.getTranslations()) {
            // El chino tiene su propio campo con analizador `cjk`, así que no pasa por normalizeLang.
            // Antes se descartaba aquí y `titleZh` se llenaba solo desde la columna de origen: como esa
            // columna guarda español en la mayoría de las referencias, el buscador en chino veía 868
            // productos de los 5.292 traducidos, y teclear el nombre chino de un artículo no lo encontraba.
            if ("zh".equalsIgnoreCase(t.getLanguage()) && esChino(t.getTitle())) {
                tituloChino = t.getTitle();
            }
            String lang = normalizeLang(t.getLanguage());
            if (lang == null) {
                continue;
            }
            doc.put("title" + capitalize(lang), t.getTitle());
            append(descriptions, t.getShortDescription());
            append(descriptions, t.getDescription());
        }
        // Manda la traducción al chino; la columna de origen solo entra como reserva y SOLO si de verdad
        // lleva ideogramas. Indexar texto español en un campo con analizador `cjk` —que no conoce las
        // palabras vacías del español— convertía cada "de", "con" o "para" en ~4.300 coincidencias con
        // peso 3: buscar "Blazer de" devolvía 4.368 resultados con el blazer buscado en tercer lugar.
        doc.put("titleZh", tituloChino != null ? tituloChino
                : (esChino(p.getTitleZh()) ? p.getTitleZh() : null));
        doc.put("descAll", descriptions.toString());
        // Atributos y variantes: son el otro sitio donde el usuario espera acertar ("Botas de nieve" vive
        // en un atributo del proveedor, "rojo"/"talla 38" en las variantes). Se indexan aplanados porque a
        // la búsqueda le basta con que el término aparezca; el detalle ya lo sirve la ficha.
        doc.put("attrs", flatten(productAttributeRepository.findByProduct_Id(p.getId()).stream()
                .map(ProductAttributeEntity::getAttrValue)));
        doc.put("variants", flattenVariants(p));
        doc.put("categoryName", categoryNames(p));
        doc.put("basePrice", p.getBasePrice());
        doc.put("trendScore", p.getTrendScore());
        doc.put("monthlySales", p.getMonthlySales());
        doc.put("rating", p.getRating());
        doc.put("inventoryCount", p.getInventoryCount());
        doc.put("hasVideo", Boolean.TRUE.equals(p.getHasVideo()));
        doc.put("freeShipping", Boolean.TRUE.equals(p.getFreeShipping()));
        doc.put("selfPickup", Boolean.TRUE.equals(p.getSelfPickup()));
        doc.put("verified", Boolean.TRUE.equals(p.getVerified()));
        doc.put("shipFrom", p.getShipFrom());
        // Como texto ISO-8601, no como Instant: el mapper de OpenSearch serializa el documento con Jackson
        // "puro" (sin los módulos de fecha que Spring registra en el suyo) y un Instant lo hacía reventar
        // entero — el producto se quedaba SIN indexar. El mapping del índice lo parsea igual como date.
        doc.put("ingestedAt", p.getIngestedAt() != null ? p.getIngestedAt().toString() : null);
        doc.put("supplierId", p.getSupplier() != null ? p.getSupplier().getId().toString() : null);
        doc.put("categoryId", p.getCategory() != null ? p.getCategory().getId().toString() : null);
        // hasImage: true solo si hay al menos una imagen ya espejada a nuestro storage (cdn_url no nulo).
        // La búsqueda filtra por este flag para no devolver productos cuya imagen no renderiza (igual
        // criterio que el filtro SQL del escaparate).
        boolean hasMirroredImage = p.getImages().stream().anyMatch(i -> i.getCdnUrl() != null);
        doc.put("hasImage", hasMirroredImage);
        if (!p.getImages().isEmpty()) {
            ProductImageEntity img = p.getImages().get(0);
            doc.put("mainImage", img.getCdnUrl() != null ? img.getCdnUrl() : img.getSourceUrl());
        }
        try {
            client.index(IndexRequest.of(b -> b.index(index).id(p.getId().toString()).document(doc)));
            return true;
        } catch (Exception e) {
            // Con la causa: el mensaje de opensearch-java para un fallo de serialización es un escueto
            // "Jackson exception" que no dice qué campo lo provocó, y con él un reindexado podía terminar
            // "correctamente" con 5.000 productos fuera del índice.
            log.error("Index failed for product {}: {} ({})", productId, e.getMessage(),
                    e.getCause() != null ? e.getCause().getMessage() : "sin causa");
            return false;
        }
    }

    /**
     * Idioma normalizado a los 7 con analizador propio en el índice ({@code es/en/pt/fr/it/de/nl}); el chino
     * va aparte en {@code titleZh}. Devuelve {@code null} para cualquier otro: mejor no indexarlo que
     * escribir un campo que el mapping no conoce (el índice es {@code dynamic:false} y lo descartaría igual,
     * pero así queda explícito).
     */
    /**
     * ¿El texto lleva al menos un ideograma CJK?
     *
     * <p>Se comprueba por CONTENIDO y no por la procedencia del campo porque `title_zh` es el título de
     * origen y también la reserva cuando falta traducción: su nombre promete chino, pero la mayoría de las
     * veces guarda español. Lo que decide si algo debe ir al campo con analizador `cjk` es lo que hay
     * escrito, no de qué columna viene.
     */
    private static boolean esChino(String texto) {
        if (texto == null || texto.isBlank()) {
            return false;
        }
        return texto.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    private static String normalizeLang(String language) {
        if (language == null) {
            return null;
        }
        String lang = language.toLowerCase().substring(0, Math.min(2, language.length()));
        return INDEXED_LANGS.contains(lang) ? lang : null;
    }

    /** Valores de las variantes (chino, base y traducciones) aplanados en un solo campo buscable. */
    private static String flattenVariants(ProductEntity p) {
        return flatten(p.getVariantOptions().stream().flatMap(o -> o.getValues().stream())
                .flatMap(v -> Stream.concat(Stream.of(v.getValueZh(), v.getValue()),
                        v.getTranslations().stream().map(VariantValueTranslationEntity::getValue))));
    }

    /** Nombre de la categoría en todos sus idiomas — buscar "botas" debe encontrar lo que cuelga de esa categoría. */
    private static String categoryNames(ProductEntity p) {
        if (p.getCategory() == null) {
            return "";
        }
        return flatten(Stream.concat(Stream.of(p.getCategory().getNameZh()),
                p.getCategory().getTranslations().stream().map(CategoryTranslationEntity::getName)));
    }

    private static String flatten(Stream<String> values) {
        return values.filter(Objects::nonNull).filter(s -> !s.isBlank()).distinct()
                .collect(Collectors.joining(" "));
    }

    private static void append(StringBuilder sb, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append(' ');
        }
        sb.append(text);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty())
            return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
