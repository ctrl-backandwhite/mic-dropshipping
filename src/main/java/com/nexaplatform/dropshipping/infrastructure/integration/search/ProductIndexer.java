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
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class ProductIndexer {

    /** Idiomas con analizador propio en el índice. El chino se indexa aparte, en {@code titleZh} (analizador cjk). */
    private static final Set<String> INDEXED_LANGS = Set.of("es", "en", "pt", "fr", "it", "de", "nl");

    private final OpenSearchClient client;
    private final ProductRepository productRepository;
    private final ProductAttributeRepository productAttributeRepository;
    private final ProductIndexSchema schema;

    /**
     * Transacción de SOLO LECTURA para construir el documento de UN producto.
     *
     * <p>Hace falta una sesión JPA porque el documento se arma recorriendo colecciones perezosas
     * —traducciones, imágenes, opciones de variante y sus valores—, y {@code findWithDetailsById} solo
     * trae por {@code @EntityGraph} el proveedor y la categoría.
     *
     * <p>Va con {@link TransactionTemplate} y no con {@code @Transactional} porque el cuerpo se invoca
     * desde esta misma clase: una llamada interna no atraviesa el proxy de Spring y la anotación sería
     * una promesa que nadie cumple. Es el mismo patrón que {@code AnuncioBusScheduler}.
     */
    private final TransactionTemplate lectura;

    public ProductIndexer(OpenSearchClient client, ProductRepository productRepository,
            ProductAttributeRepository productAttributeRepository, ProductIndexSchema schema,
            PlatformTransactionManager gestorDeTransacciones) {
        this.client = client;
        this.productRepository = productRepository;
        this.productAttributeRepository = productAttributeRepository;
        this.schema = schema;
        this.lectura = new TransactionTemplate(gestorDeTransacciones);
        this.lectura.setReadOnly(true);
    }

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

    /**
     * Cuántos documentos tiene el índice ahora mismo, o −1 si no se puede preguntar.
     *
     * <p>Se usa para comprobar al arrancar que el índice está COMPLETO. Devuelve −1 en vez de cero cuando
     * falla: cero significaría «índice vacío» y dispararía una alarma cada vez que el buscador está caído,
     * que es justo cuando menos falta hace añadir ruido.
     */
    public long documentCount() {
        try {
            return client.count(c -> c.index(indexName())).count();
        } catch (OpenSearchException | IOException e) {
            log.debug("No se ha podido contar el índice '{}': {}", indexName(), e.getMessage());
            return -1;
        }
    }

    // El parámetro es un Map y no ProductIngestedEvent a propósito: el consumidor deserializa SIEMPRE
    // a mapa, así que declarar el tipo hacía que Spring no supiera convertirlo y el listener reventara
    // con CADA mensaje del tema, reintentando sin fin. La conversión vive en ProductIngestedEvent.desde.
    @KafkaListener(topics = NexaTopics.PRODUCT_INGESTED, groupId = "nexadrop-search-indexer")
    public void onProductIngested(Map<String, Object> mensaje) {
        ProductIngestedEvent event = ProductIngestedEvent.desde(mensaje);
        if (event == null) {
            log.warn("Indexado: mensaje de product.ingested sin identificador utilizable, se ignora");
            return;
        }
        indexaProducto(event.productId());
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
     * Reindexa el catálogo entero en OpenSearch. Devuelve cuántos entraron de verdad en el índice.
     *
     * <p><b>Este método NO abre transacción, y es deliberado.</b> Antes llevaba
     * {@code @Transactional(readOnly = true)} para mantener una sesión JPA abierta durante todo el
     * barrido y poder recorrer las colecciones perezosas de cada producto. Funcionaba, y a cambio
     * dejaba UNA transacción de Postgres abierta de principio a fin: medido en local sobre 7.646
     * productos, <b>757 segundos</b>, el 27,7 % de ellos en estado «idle in transaction» mientras
     * esperaba a cada llamada HTTP a OpenSearch.
     *
     * <p>Lo que eso provocó el 12-sep-2026: esa transacción retiene {@code AccessShareLock} sobre
     * {@code product} y sus tablas hijas hasta que confirma. El {@code ALTER TABLE} de la migración
     * v170 pidió cerrojo exclusivo, se encoló detrás, y una petición de cerrojo exclusivo EN ESPERA
     * bloquea a todo lo que llegue después —incluidos los SELECT—: el catálogo de preproducción
     * estuvo diecisiete minutos parado y el pod murió al agotar la sonda de arranque.
     *
     * <p>Ahora cada producto se lee en su propia transacción corta y la llamada a OpenSearch ocurre
     * FUERA de ella. Lo fija {@code ReindexTransactionBoundariesIT}.
     */
    public int reindexAll() {
        // PURGA primero: borra los documentos obsoletos (productos ya eliminados de la BD) para que el
        // índice quede EXACTAMENTE igual que la BD. Sin esto, un producto borrado seguía apareciendo en
        // la búsqueda (documento huérfano con un UUID que ya no existe) porque el reindex solo hacía upsert.
        purgeIndex();
        // Solo los ids: con findAll() el barrido se quedaba con miles de entidades vivas en la sesión.
        List<UUID> ids = lectura.execute(estado -> productRepository.findAllIds());
        int[] counters = { 0, 0 };
        for (UUID id : Objects.requireNonNullElse(ids, List.<UUID>of())) {
            if (indexaProducto(id)) {
                counters[0]++;
            } else {
                counters[1]++;
            }
        }
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

    public void indexProduct(UUID productId) {
        indexaProducto(productId);
    }

    /**
     * Indexa UN producto: arma el documento dentro de una transacción de solo lectura y lo manda a
     * OpenSearch FUERA de ella.
     *
     * <p><b>Esa separación es el invariante de esta clase.</b> La llamada a OpenSearch es de red y
     * bloquea; hacerla con la transacción abierta deja la conexión de Postgres «idle in transaction»
     * reteniendo sus cerrojos durante todo el viaje. Con un producto suelto el daño es pequeño; con el
     * barrido entero fue lo que congeló el catálogo de preproducción diecisiete minutos. Lo comprueba
     * {@code ReindexTransactionBoundariesIT}.
     */
    private boolean indexaProducto(UUID productId) {
        Map<String, Object> doc = lectura.execute(estado -> construyeDoc(productId));
        if (doc == null) {
            return false;
        }
        try {
            client.index(IndexRequest.of(b -> b.index(index).id(productId.toString()).document(doc)));
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
     * Arma el documento de un producto. Corre SIEMPRE dentro de {@link #lectura}: recorre colecciones
     * perezosas y fuera de sesión reventaría. Devuelve {@code null} si el producto ya no existe.
     */
    private Map<String, Object> construyeDoc(UUID productId) {
        ProductEntity p = productRepository.findWithDetailsById(productId).orElse(null);
        if (p == null)
            return null;
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
        // Las fotos de la DESCRIPCION no cuentan: el escaparate filtra por `hasImage` para no enseñar
        // productos cuya imagen no renderiza, y un producto cuya unica foto espejada fuese un cartel
        // de la descripcion saldria en el listado sin una sola foto de galeria que enseñar.
        List<ProductImageEntity> deGaleria = p.getImages().stream()
                .filter(i -> !"DETAIL".equalsIgnoreCase(i.getRole())).toList();
        boolean hasMirroredImage = deGaleria.stream().anyMatch(i -> i.getCdnUrl() != null);
        doc.put("hasImage", hasMirroredImage);
        if (!deGaleria.isEmpty()) {
            ProductImageEntity img = deGaleria.get(0);
            doc.put("mainImage", img.getCdnUrl() != null ? img.getCdnUrl() : img.getSourceUrl());
        }
        return doc;
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
