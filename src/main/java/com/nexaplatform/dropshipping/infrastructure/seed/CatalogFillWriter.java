package com.nexaplatform.dropshipping.infrastructure.seed;

import com.github.slugify.Slugify;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestProductRequest;
import com.nexaplatform.dropshipping.application.service.ProductSeoMetadata;
import com.nexaplatform.dropshipping.application.usecase.CatalogUseCase;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Persists a single generated demo product in its own transaction: ingests it via
 * the real catalog pipeline (so the Kafka {@code product.ingested} event is emitted
 * and the search index is kept in sync), attaches the four UI translations and
 * publishes it (DRAFT → ACTIVE + trend score). Kept as a separate bean so each
 * product commits independently, avoiding one giant seed transaction for 700 rows.
 */
@Component
@RequiredArgsConstructor
public class CatalogFillWriter {

    private static final Slugify SLUG = Slugify.builder().build();

    private final CatalogUseCase catalogService;
    private final ProductRepository productRepository;

    /** Título y descripción de un idioma: viajan juntos porque siempre se escriben en pareja. */
    public record LocalizedText(String title, String description) {
    }

    /** Los textos del producto en los cuatro idiomas de la carga. */
    public record ProductTexts(LocalizedText es, LocalizedText en, LocalizedText pt, LocalizedText zh) {
    }

    /** Legacy 6-arg shape used by the demo filler: PT falls back to ES, no extra enrichment. */
    @Transactional
    public UUID write(IngestProductRequest req, String esTitle, String enTitle, String zhTitle, String esDesc,
            String enDesc) {
        return persist(req, new ProductTexts(new LocalizedText(esTitle, esDesc), new LocalizedText(enTitle, enDesc),
                new LocalizedText(esTitle, esDesc), new LocalizedText(zhTitle, esDesc)), p -> {
                });
    }

    /**
     * Full variant: per-language titles AND descriptions, plus an {@code enrich} hook that runs on the
     * managed entity inside this transaction (used by the bulk importer to set logistics/customs fields
     * without hitting a LazyInitializationException).
     */
    @Transactional
    public UUID write(IngestProductRequest req, ProductTexts texts, Consumer<ProductEntity> enrich) {
        return persist(req, texts, enrich);
    }

    /**
     * Firma heredada (títulos y descripciones sueltos). Se mantiene porque la usan el importador masivo y
     * sus pruebas; delega en la variante con {@link ProductTexts}, que es la que hay que usar en código nuevo.
     */
    @SuppressWarnings("java:S107") // firma heredada: la corta con ProductTexts es la buena
    @Transactional
    public UUID write(IngestProductRequest req, String esTitle, String enTitle, String ptTitle, String zhTitle,
            String descEs, String descEn, String descPt, String descZh,
            Consumer<ProductEntity> enrich) {
        return persist(req, new ProductTexts(new LocalizedText(esTitle, descEs), new LocalizedText(enTitle, descEn),
                new LocalizedText(ptTitle, descPt), new LocalizedText(zhTitle, descZh)), enrich);
    }

    /**
     * Alta/actualización real del producto. Privada y sin anotación: las sobrecargas públicas se llamaban
     * entre sí por {@code this}, de modo que el proxy de Spring no intervenía y el {@code @Transactional}
     * de la llamada interna no se aplicaba. Ahora la transacción se abre siempre en el punto de entrada.
     */
    private UUID persist(IngestProductRequest req, ProductTexts texts, Consumer<ProductEntity> enrich) {
        String esTitle = texts.es().title();
        String descEs = texts.es().description();
        ProductEntity saved = catalogService.upsertProduct(req);
        ProductEntity managed = productRepository.findById(saved.getId()).orElse(null);
        if (managed == null)
            return saved.getId();

        // Upsert in-place por idioma. NO usamos clear()+add: con orphanRemoval, Hibernate ejecuta los
        // INSERT antes que los DELETE en el flush, y al reimportar un producto existente chocaría con la
        // unique (product_id, language) [ux_prodtr_prod_lang]. Actualizando la fila existente (o creándola
        // si falta) la reimportación es idempotente y nunca viola la constraint.
        //
        // El reflejo del español sobre inglés y portugués (el `blankTo` de abajo) se conserva: un título
        // latino sirve de apaño legible mientras llega la traducción. Lo que NO se refleja es el chino —
        // de eso se encarga upsertTranslation, que descarta el texto en chino en toda fila que no sea la
        // del propio chino.
        upsertTranslation(managed, "es", esTitle, descEs);
        upsertTranslation(managed, "en", blankTo(texts.en().title(), esTitle),
                blankTo(texts.en().description(), descEs));
        upsertTranslation(managed, "zh", texts.zh().title(), blankTo(texts.zh().description(), descEs));
        upsertTranslation(managed, "pt", blankTo(texts.pt().title(), esTitle),
                blankTo(texts.pt().description(), descEs));

        if (enrich != null) {
            enrich.accept(managed);
        }

        // upsertProduct construye el slug con el título ZH y Slugify descarta los caracteres CJK: con un
        // título chino REAL el slug quedaba en "-<externalId>". Aquí sí tenemos el título ES, así que se
        // reconstruye para que la URL sea legible. Solo se toca el degradado, nunca un slug ya válido
        // (así la reimportación de un producto existente no le cambia la URL).
        String slug = managed.getSlug();
        if (slug == null || slug.isBlank() || slug.startsWith("-")) {
            String base = SLUG.slugify(esTitle);
            if (!base.isBlank()) {
                if (base.length() > 100) {
                    base = base.substring(0, 100);
                }
                // externalId es único, así que base-externalId no puede colisionar.
                String full = base + "-" + req.externalId().toLowerCase();
                managed.setSlug(full.length() > 220 ? full.substring(0, 220) : full);
            }
        }

        // Publish straight away (mirrors DemoCatalogSeedRunner.publishAll trend formula).
        managed.setStatus(ProductStatus.ACTIVE);
        double salesNorm = Math.min(1.0, managed.getMonthlySales() / 1000.0);
        double rating = managed.getRating() != null ? managed.getRating().doubleValue() / 5.0 : 0;
        double repurchase = managed.getRepurchaseRate() != null ? managed.getRepurchaseRate().doubleValue() / 100.0 : 0;
        double reviews = Math.min(1.0, managed.getReviewCount() / 500.0);
        double score = 0.4 * salesNorm + 0.3 * rating + 0.2 * repurchase + 0.1 * reviews;
        managed.setTrendScore(BigDecimal.valueOf(Math.round(score * 10000) / 10000.0));

        productRepository.save(managed);
        return saved.getId();
    }

    /**
     * Crea o actualiza la traducción del idioma sin borrar/reinsertar (evita el choque de unique).
     *
     * <p><b>El texto escrito en chino solo entra en la fila del chino.</b> Los volcados de 1688 llegan a
     * menudo con un único idioma —{@code translations: {"zh": {...}}}—; el importador toma ese título como
     * canónico (lo necesita para validar la fila, derivar el identificador externo y nombrar las variantes)
     * y desde ahí se reflejaba en los cuatro idiomas, de modo que el catálogo acababa con el mismo
     * «破洞牛仔裤男春季2025浅色修身弹力九分裤» en es, en, pt y zh. Guardarlo así no es solo feo en la ficha:
     * ensucia el índice de búsqueda —el título entra en el campo español, cuyo analizador no sabe nada de
     * ideogramas— y obliga a {@link ProductSeoMetadata} a limpiar después unos metadatos que nunca
     * debieron generarse. Es la misma regla que ya aplica el SEO (DROP-686): en un idioma que no es el
     * chino, un texto con ideogramas está contaminado.
     *
     * <p>Cuando el texto se descarta <b>no se crea la fila y no se toca la que hubiera</b>. Ninguna de las
     * dos cosas es un detalle: una fila con el título vacío es peor que no tenerla, porque la ficha busca
     * la traducción por idioma y pintaría un producto SIN NOMBRE (la del listado sí descarta los títulos en
     * blanco, la de la ficha no); y respetar la fila existente evita que reimportar un volcado que vuelve a
     * venir solo en chino se lleve por delante una traducción buena escrita a mano en el panel.
     */
    private static void upsertTranslation(ProductEntity p, String lang, String title, String desc) {
        boolean filaDelChino = "zh".equalsIgnoreCase(lang);
        if (!filaDelChino && escritoEnChino(title)) {
            return;
        }
        String texto = !filaDelChino && escritoEnChino(desc) ? null : desc;
        String shortDesc = texto != null && texto.length() > 2000 ? texto.substring(0, 2000) : texto;
        ProductTranslationEntity existing = p.getTranslations().stream()
                .filter(t -> lang.equalsIgnoreCase(t.getLanguage())).findFirst().orElse(null);
        if (existing != null) {
            existing.setTitle(title);
            existing.setShortDescription(shortDesc);
            existing.setDescription(texto);
        } else {
            p.getTranslations().add(ProductTranslationEntity.builder().product(p).language(lang).title(title)
                    .shortDescription(shortDesc).description(texto).provider("seed").build());
        }
    }

    /**
     * ¿El texto está escrito en chino? Lleva ideogramas y no lleva ni una sola letra latina.
     *
     * <p>Las dos condiciones son necesarias, y la segunda es la que evita el daño colateral: los volcados
     * de 1688 cuelan ideogramas sueltos dentro de textos ya traducidos («Sandalias de tacón 露趾»), y esos
     * títulos SÍ tienen que guardarse en su idioma —quitarlos dejaría al producto sin nombre en español
     * por un carácter—. Lo que aquí se rechaza es el título del proveedor tal cual, que no trae ni una
     * letra latina; los ideogramas sueltos ya los limpia {@link ProductSeoMetadata} donde importa.
     *
     * <p>La detección de ideogramas se reutiliza de {@link ProductSeoMetadata#hasCjk} a propósito: dos
     * definiciones de «esto es chino» conviviendo en el mismo flujo de carga terminan separándose, y
     * entonces el mismo título se guardaría o no según por qué método haya pasado.
     */
    /**
     * ¿Es este texto chino sin traducir? Basta con que lleve UN ideograma.
     *
     * <p>Antes se exigía además que no hubiera ninguna letra latina, y esa condición anulaba el guardián
     * entero: los títulos de 1688 mezclan casi siempre («tiktok欧美2026新款夏季…», «T恤男拼接圆领上衣»,
     * «a字裙», «刺绣logo»), así que una sola letra suelta bastaba para que el título chino se copiara tal
     * cual a la fila española. 36 productos acabaron con el título en chino en es/en/pt.
     *
     * <p>El criterio bueno es más simple: un título español, inglés o portugués DE VERDAD no lleva
     * ideogramas. Si los lleva, no es una traducción, y la fila se deja sin escribir antes que mentir.
     */
    /**
     * Si el texto está ESCRITO en chino, que no es lo mismo que contener algún ideograma.
     *
     * <p>La diferencia importa: un título ya traducido puede arrastrar un resto sin traducir
     * —«Sandalias de tacón 露趾»—, y descartarlo por esos dos caracteres dejaría el producto sin
     * título en español, que es mucho peor que un título con un residuo. Se mira la proporción: si
     * los ideogramas no llegan a la mitad de lo escrito, el texto es de otro idioma.
     */
    private static boolean escritoEnChino(String texto) {
        if (!ProductSeoMetadata.hasCjk(texto)) {
            return false;
        }
        long cjk = texto.codePoints().filter(Character::isLetter)
                .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN).count();
        long letras = texto.codePoints().filter(Character::isLetter).count();
        return letras > 0 && cjk * 2 > letras;
    }

    private static String blankTo(String v, String fallback) {
        return v != null && !v.isBlank() ? v : fallback;
    }
}
