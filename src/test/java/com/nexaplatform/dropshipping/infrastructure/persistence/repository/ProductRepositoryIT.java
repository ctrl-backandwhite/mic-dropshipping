package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.config.PersistenceITBase;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductAttributeEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT de persistencia para {@link ProductJpaRepositoryAdapter}: ejercita sus @Query / finders
 * derivados contra Postgres real (Testcontainers, esquema Liquibase). @DataJpaTest es transaccional
 * con rollback por test, así que cada método parte de tablas limpias.
 *
 * <p>Cobertura: findBySlug, findBySourceAndExternalId, findFirstByExternalId, findWithDetailsBySlug,
 * findWithDetailsById (fetch joins), findByStatus, findTopByTrendScore (orden + filtro de imagen
 * espejada), findByCategoryOrderByTrend y searchStorefront (needle + filtros + EXISTS imagen cdn).
 */
class ProductRepositoryIT extends PersistenceITBase {

    @Autowired
    ProductJpaRepositoryAdapter repo;

    /** The interface the admin use-case actually depends on; carries {@link ProductRepository#searchAdmin}. */
    @Autowired
    ProductRepository adminRepo;

    @Autowired
    TestEntityManager em;

    // ── Helpers de construcción ──────────────────────────────────────────────

    /** Builder de producto con todas las columnas NOT NULL ya rellenas. */
    private ProductEntity.ProductEntityBuilder baseProduct(String slug, String externalId) {
        return ProductEntity.builder()
                .slug(slug)
                .externalId(externalId)
                .source("1688")          // NOT NULL
                .titleZh("默认标题")        // title_zh NOT NULL
                .moq(1)                  // NOT NULL (int)
                .status(ProductStatus.ACTIVE) // NOT NULL enum
                .reviewCount(0)          // NOT NULL (int)
                .monthlySales(0)         // NOT NULL (int)
                .currency("CNY")
                .basePrice(new BigDecimal("10.0000"));
    }

    private SupplierEntity persistSupplier(String externalId) {
        SupplierEntity supplier = SupplierEntity.builder()
                .externalId(externalId) // NOT NULL
                .source("1688")         // NOT NULL
                .name("Supplier " + externalId)
                .verified(true)         // NOT NULL (boolean)
                .trustPass(true)        // NOT NULL (boolean)
                .build();
        return em.persist(supplier);
    }

    private CategoryEntity persistCategory(String slug) {
        CategoryEntity category = CategoryEntity.builder()
                .slug(slug)             // NOT NULL unique
                .position(0)            // NOT NULL (int)
                .active(true)           // NOT NULL (boolean)
                .source("1688")
                .build();
        return em.persist(category);
    }

    /** Adjunta una imagen al producto (cascade ALL desde ProductEntity.images). */
    private void addImage(ProductEntity product, String cdnUrl) {
        ProductImageEntity image = ProductImageEntity.builder()
                .product(product)
                .position(0)            // NOT NULL (int)
                .sourceUrl("https://src/" + product.getSlug() + ".jpg") // NOT NULL
                .cdnUrl(cdnUrl)         // nullable: null = sin espejar
                .build();
        product.getImages().add(image);
    }

    // ── Finders derivados simples ────────────────────────────────────────────

    @Test
    void findBySlug_returnsMatchingProduct() {
        ProductEntity saved = repo.save(baseProduct("zapatillas-run", "EXT-1").build());
        em.flush();
        em.clear();

        Optional<ProductEntity> found = repo.findBySlug("zapatillas-run");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
        assertThat(repo.findBySlug("no-existe")).isEmpty();
    }

    @Test
    void findBySourceAndExternalId_matchesOnBothColumns() {
        repo.save(baseProduct("p-source", "SKU-99").build());
        em.flush();
        em.clear();

        assertThat(repo.findBySourceAndExternalId("1688", "SKU-99")).isPresent();
        // Misma external id, source distinto → no debe encontrar.
        assertThat(repo.findBySourceAndExternalId("aliexpress", "SKU-99")).isEmpty();
    }

    @Test
    void findFirstByExternalId_resolvesWithoutKnowingSource() {
        repo.save(baseProduct("p-ext", "WEBHOOK-7").build());
        em.flush();
        em.clear();

        assertThat(repo.findFirstByExternalId("WEBHOOK-7")).isPresent();
        assertThat(repo.findFirstByExternalId("nope")).isEmpty();
    }

    // ── Fetch joins (@EntityGraph supplier + category) ───────────────────────

    @Test
    void findWithDetailsBySlug_fetchesSupplierAndCategory() {
        SupplierEntity supplier = persistSupplier("SUP-1");
        CategoryEntity category = persistCategory("cat-detalle");
        ProductEntity product = baseProduct("detalle-slug", "EXT-D").build();
        product.setSupplier(supplier);
        product.setCategory(category);
        repo.save(product);
        em.flush();
        em.clear(); // fuerza lectura desde BD para validar el entity graph

        Optional<ProductEntity> found = repo.findWithDetailsBySlug("detalle-slug");

        assertThat(found).isPresent();
        assertThat(found.get().getSupplier().getExternalId()).isEqualTo("SUP-1");
        assertThat(found.get().getCategory().getSlug()).isEqualTo("cat-detalle");
    }

    @Test
    void findWithDetailsById_fetchesSupplierAndCategory() {
        SupplierEntity supplier = persistSupplier("SUP-2");
        CategoryEntity category = persistCategory("cat-by-id");
        ProductEntity product = baseProduct("detalle-by-id", "EXT-DI").build();
        product.setSupplier(supplier);
        product.setCategory(category);
        UUID id = repo.save(product).getId();
        em.flush();
        em.clear();

        Optional<ProductEntity> found = repo.findWithDetailsById(id);

        assertThat(found).isPresent();
        assertThat(found.get().getSupplier().getExternalId()).isEqualTo("SUP-2");
        assertThat(found.get().getCategory().getSlug()).isEqualTo("cat-by-id");
    }

    // ── findByStatus (admin: cualquier producto, con o sin imagen) ───────────

    @Test
    void findByStatus_returnsAllProductsOfStatusIncludingThoseWithoutImage() {
        repo.save(baseProduct("activo-1", "A1").status(ProductStatus.ACTIVE).build());
        repo.save(baseProduct("borrador-1", "B1").status(ProductStatus.DRAFT).build());
        em.flush();
        em.clear();

        Page<ProductEntity> active = repo.findByStatus(ProductStatus.ACTIVE, PageRequest.of(0, 10));
        Page<ProductEntity> draft = repo.findByStatus(ProductStatus.DRAFT, PageRequest.of(0, 10));

        assertThat(active.getContent()).extracting(ProductEntity::getSlug).containsExactly("activo-1");
        assertThat(draft.getContent()).extracting(ProductEntity::getSlug).containsExactly("borrador-1");
    }

    // ── findTopByTrendScore (filtro imagen cdn + orden trendScore DESC) ──────

    @Test
    void findTopByTrendScore_onlyVisibleAndOrderedByTrendDesc() {
        // Visible alto trend (imagen espejada)
        ProductEntity high = baseProduct("trend-high", "T-HIGH").trendScore(new BigDecimal("90.0")).build();
        addImage(high, "https://cdn/high.jpg");
        repo.save(high);

        // Visible bajo trend (imagen espejada)
        ProductEntity low = baseProduct("trend-low", "T-LOW").trendScore(new BigDecimal("10.0")).build();
        addImage(low, "https://cdn/low.jpg");
        repo.save(low);

        // NO visible: imagen sin cdn_url (no espejada) → debe excluirse aunque tenga trend altísimo
        ProductEntity noCdn = baseProduct("trend-nocdn", "T-NOCDN").trendScore(new BigDecimal("999.0")).build();
        addImage(noCdn, null);
        repo.save(noCdn);

        // NO visible: sin imagen alguna
        repo.save(baseProduct("trend-noimg", "T-NOIMG").trendScore(new BigDecimal("500.0")).build());

        // NO visible: estado DRAFT pese a imagen espejada
        ProductEntity draft = baseProduct("trend-draft", "T-DRAFT")
                .status(ProductStatus.DRAFT).trendScore(new BigDecimal("888.0")).build();
        addImage(draft, "https://cdn/draft.jpg");
        repo.save(draft);

        em.flush();
        em.clear();

        Page<ProductEntity> page = repo.findTopByTrendScore(ProductStatus.ACTIVE, PageRequest.of(0, 10));

        assertThat(page.getContent())
                .extracting(ProductEntity::getSlug)
                .containsExactly("trend-high", "trend-low"); // orden DESC y solo visibles ACTIVE
    }

    // ── findByCategoryOrderByTrend (categoría + visible + orden trend) ───────

    @Test
    void findByCategoryOrderByTrend_filtersByCategoryAndVisibilityOrderedByTrend() {
        CategoryEntity catA = persistCategory("cat-a");
        CategoryEntity catB = persistCategory("cat-b");

        ProductEntity a1 = baseProduct("a-top", "A-TOP").trendScore(new BigDecimal("50.0")).build();
        a1.setCategory(catA);
        addImage(a1, "https://cdn/a-top.jpg");
        repo.save(a1);

        ProductEntity a2 = baseProduct("a-low", "A-LOW").trendScore(new BigDecimal("5.0")).build();
        a2.setCategory(catA);
        addImage(a2, "https://cdn/a-low.jpg");
        repo.save(a2);

        // Misma categoría pero sin imagen espejada → excluido
        ProductEntity a3 = baseProduct("a-nocdn", "A-NOCDN").trendScore(new BigDecimal("999.0")).build();
        a3.setCategory(catA);
        addImage(a3, null);
        repo.save(a3);

        // Otra categoría → excluido
        ProductEntity b1 = baseProduct("b-prod", "B-PROD").trendScore(new BigDecimal("80.0")).build();
        b1.setCategory(catB);
        addImage(b1, "https://cdn/b.jpg");
        repo.save(b1);

        em.flush();
        em.clear();

        Page<ProductEntity> page = repo.findByCategoryOrderByTrend(
                catA.getId(), ProductStatus.ACTIVE, PageRequest.of(0, 10));

        assertThat(page.getContent())
                .extracting(ProductEntity::getSlug)
                .containsExactly("a-top", "a-low");
    }

    // ── searchStorefront (needle + filtros + paginación + EXISTS imagen) ─────

    @Test
    void searchStorefront_withAllFiltersNull_returnsOnlyVisibleActive() {
        ProductEntity visible = baseProduct("sf-visible", "SF-V").build();
        addImage(visible, "https://cdn/sf-v.jpg");
        repo.save(visible);

        // Sin imagen espejada → fuera
        ProductEntity hidden = baseProduct("sf-hidden", "SF-H").build();
        addImage(hidden, null);
        repo.save(hidden);

        em.flush();
        em.clear();

        Page<ProductEntity> page = repo.searchStorefront(
                ProductStatus.ACTIVE, null, null, null, null, null,
                null, null, null, null, null, null, PageRequest.of(0, 20));

        assertThat(page.getContent())
                .extracting(ProductEntity::getSlug)
                .containsExactly("sf-visible");
    }

    @Test
    void searchStorefront_needleMatchesSlugTitleAndTranslation() {
        // Match por slug
        ProductEntity bySlug = baseProduct("camiseta-roja", "SLUG-MATCH").build();
        addImage(bySlug, "https://cdn/slug.jpg");
        repo.save(bySlug);

        // Match por externalId (en minúsculas)
        ProductEntity byExternal = baseProduct("producto-x", "camiseta-ext").build();
        addImage(byExternal, "https://cdn/ext.jpg");
        repo.save(byExternal);

        // Match por traducción (title)
        ProductEntity byTranslation = baseProduct("producto-y", "PY").build();
        addImage(byTranslation, "https://cdn/tr.jpg");
        ProductTranslationEntity tr = ProductTranslationEntity.builder()
                .product(byTranslation)
                .language("es")        // NOT NULL
                .title("Camiseta de algodón")
                .build();
        byTranslation.getTranslations().add(tr);
        repo.save(byTranslation);

        // Sin relación con el needle → fuera
        ProductEntity unrelated = baseProduct("pantalon-azul", "PZ").build();
        addImage(unrelated, "https://cdn/un.jpg");
        repo.save(unrelated);

        em.flush();
        em.clear();

        Page<ProductEntity> page = repo.searchStorefront(
                ProductStatus.ACTIVE, "camiseta", null, null, null, null,
                null, null, null, null, null, null, PageRequest.of(0, 20));

        assertThat(page.getContent())
                .extracting(ProductEntity::getSlug)
                .containsExactlyInAnyOrder("camiseta-roja", "producto-x", "producto-y");
    }

    @Test
    void searchStorefront_needleMatchesAttribute() {
        ProductEntity withAttr = baseProduct("prod-attr", "PA").build();
        addImage(withAttr, "https://cdn/attr.jpg");
        repo.save(withAttr);
        em.flush(); // persistimos producto antes del atributo (FK)

        ProductAttributeEntity attr = ProductAttributeEntity.builder()
                .product(withAttr)
                .attrKey("brand")     // NOT NULL
                .attrValue("nike")    // NOT NULL
                .build();
        em.persist(attr);
        em.flush();
        em.clear();

        Page<ProductEntity> page = repo.searchStorefront(
                ProductStatus.ACTIVE, "nike", null, null, null, null,
                null, null, null, null, null, null, PageRequest.of(0, 20));

        assertThat(page.getContent())
                .extracting(ProductEntity::getSlug)
                .containsExactly("prod-attr");
    }

    @Test
    void searchStorefront_appliesCategoryPriceShippingAndRatingFilters() {
        CategoryEntity cat = persistCategory("cat-filter");

        // Cumple TODOS los filtros
        ProductEntity match = baseProduct("match", "M1").build();
        match.setCategory(cat);
        match.setBasePrice(new BigDecimal("50.0000"));
        match.setShipFrom("CN");
        match.setFreeShipping(true);
        match.setRating(new BigDecimal("4.50"));
        match.setInventoryCount(100);
        addImage(match, "https://cdn/match.jpg");
        repo.save(match);

        // Precio fuera de rango (demasiado caro)
        ProductEntity tooExpensive = baseProduct("caro", "M2").build();
        tooExpensive.setCategory(cat);
        tooExpensive.setBasePrice(new BigDecimal("500.0000"));
        tooExpensive.setShipFrom("CN");
        tooExpensive.setFreeShipping(true);
        tooExpensive.setRating(new BigDecimal("4.50"));
        tooExpensive.setInventoryCount(100);
        addImage(tooExpensive, "https://cdn/caro.jpg");
        repo.save(tooExpensive);

        // shipFrom distinto
        ProductEntity wrongShip = baseProduct("otro-origen", "M3").build();
        wrongShip.setCategory(cat);
        wrongShip.setBasePrice(new BigDecimal("50.0000"));
        wrongShip.setShipFrom("US");
        wrongShip.setFreeShipping(true);
        wrongShip.setRating(new BigDecimal("4.50"));
        wrongShip.setInventoryCount(100);
        addImage(wrongShip, "https://cdn/ship.jpg");
        repo.save(wrongShip);

        em.flush();
        em.clear();

        Page<ProductEntity> page = repo.searchStorefront(
                ProductStatus.ACTIVE, null, cat.getId(), null,
                new BigDecimal("10.0000"), new BigDecimal("100.0000"),
                "CN", true, null, null, new BigDecimal("4.00"), 10,
                PageRequest.of(0, 20));

        assertThat(page.getContent())
                .extracting(ProductEntity::getSlug)
                .containsExactly("match");
    }

    @Test
    void searchStorefront_honoursPaginationAndSort() {
        for (int i = 0; i < 5; i++) {
            ProductEntity p = baseProduct("page-" + i, "PG-" + i).build();
            p.setBasePrice(new BigDecimal(String.valueOf(i + 1)));
            addImage(p, "https://cdn/page-" + i + ".jpg");
            repo.save(p);
        }
        em.flush();
        em.clear();

        Pageable firstTwoByPriceAsc = PageRequest.of(0, 2, Sort.by(Sort.Direction.ASC, "basePrice"));
        Page<ProductEntity> page = repo.searchStorefront(
                ProductStatus.ACTIVE, null, null, null, null, null,
                null, null, null, null, null, null, firstTwoByPriceAsc);

        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getTotalPages()).isEqualTo(3);
        List<String> slugs = page.getContent().stream().map(ProductEntity::getSlug).toList();
        assertThat(slugs).containsExactly("page-0", "page-1"); // dos más baratos, en orden
    }

    // ── searchAdmin (whole catalogue, all languages, status-agnostic, image-agnostic) ─────

    /**
     * The admin search must find a product by its Spanish translation title even when it has NO mirrored
     * image and is NOT ACTIVE — the exact case that the old client-side, single-page, single-language filter
     * missed. It must also match other languages (Chinese title) and honour the optional status filter.
     */
    @Test
    void searchAdmin_matchesAllLanguages_ignoringStatusAndImage() {
        // Spanish-only match, PAUSED and WITHOUT image → storefront would hide it, admin must find it.
        ProductEntity spanish = baseProduct("sandalias-tacon-alto", "SND-ES")
                .status(ProductStatus.PAUSED)
                .titleZh("默认标题")
                .build();
        ProductTranslationEntity es = ProductTranslationEntity.builder()
                .product(spanish)
                .language("es") // NOT NULL
                .title("Sandalias de tacón alto transparente para mujer, punta cuadrada, tacón grueso")
                .build();
        spanish.getTranslations().add(es);
        repo.save(spanish);

        // Chinese-title match (multilingual), ARCHIVED and without image.
        ProductEntity chinese = baseProduct("producto-chino", "SND-ZH")
                .status(ProductStatus.ARCHIVED)
                .titleZh("透明高跟凉鞋")
                .build();
        repo.save(chinese);

        // Unrelated product → must never match the needle.
        repo.save(baseProduct("pantalon-vaquero", "UNREL").build());

        em.flush();
        em.clear();

        Pageable firstPage = PageRequest.of(0, 20);

        // Spanish full-ish title finds the PAUSED, image-less product across the whole catalogue.
        assertThat(adminRepo.searchAdmin(null, null, "sandalias de tacón", null, "es", false, null, null, null, null, null, null, firstPage).getContent())
                .extracting(ProductEntity::getSlug)
                .containsExactly("sandalias-tacon-alto");

        // Chinese needle finds the ARCHIVED product → multilingual.
        assertThat(adminRepo.searchAdmin(null, null, "透明", null, "es", false, null, null, null, null, null, null, firstPage).getContent())
                .extracting(ProductEntity::getSlug)
                .containsExactly("producto-chino");

        // Optional status filter still narrows results.
        assertThat(adminRepo.searchAdmin(ProductStatus.PAUSED, null, "sandalias", null, "es", false, null, null, null, null, null, null, firstPage).getContent())
                .extracting(ProductEntity::getSlug)
                .containsExactly("sandalias-tacon-alto");
        assertThat(adminRepo.searchAdmin(ProductStatus.ACTIVE, null, "sandalias", null, "es", false, null, null, null, null, null, null, firstPage).getContent())
                .isEmpty();

        // A needle that matches nothing returns an empty page.
        assertThat(adminRepo.searchAdmin(null, null, "zzz-no-match", null, "es", false, null, null, null, null, null, null, firstPage).getContent()).isEmpty();
    }

    /**
     * El rango por fecha de CARGA acota de verdad, y el límite superior es EXCLUSIVO.
     *
     * <p>Esta condición es lo que permite que la exportación del panel y su lista sean la misma consulta.
     * Antes la exportación tenía su propio SQL: quien filtraba la lista por fechas y pulsaba «Exportar»
     * se bajaba el catálogo entero sin que nada avisara. Si este filtro dejara de acotar, el defecto
     * volvería por la puerta de atrás y otra vez en silencio.
     *
     * <p>El límite superior es exclusivo porque el panel manda DÍAS, no instantes: «hasta el 17» tiene
     * que incluir todo el 17, y se traduce a «antes del 18 a las 00:00».
     */
    @Test
    void searchAdmin_acotaPorFechaDeCargaConElLimiteSuperiorExclusivo() {
        Instant elQuince = Instant.parse("2026-09-15T10:00:00Z");
        Instant elDiecisiete = Instant.parse("2026-09-17T23:30:00Z");
        // JUSTO en el límite: es el único dato que distingue un tope exclusivo de uno inclusivo.
        Instant elDieciocho = Instant.parse("2026-09-18T00:00:00Z");
        repo.save(baseProduct("cargado-el-15", "FEC-15").ingestedAt(elQuince).build());
        repo.save(baseProduct("cargado-el-17", "FEC-17").ingestedAt(elDiecisiete).build());
        repo.save(baseProduct("cargado-el-18", "FEC-18").ingestedAt(elDieciocho).build());
        // La columna es opcional y hay fichas viejas sin ella: entran cuando NO se acota, y no cuando sí.
        repo.save(baseProduct("sin-fecha-de-carga", "FEC-00").build());
        em.flush();
        em.clear();

        Pageable firstPage = PageRequest.of(0, 20);
        Instant desdeEl16 = Instant.parse("2026-09-16T00:00:00Z");
        Instant antesDel18 = Instant.parse("2026-09-18T00:00:00Z");

        // Del 16 al 17 (ambos incluidos): entra el del 17 y ni el del 15 ni el del 18.
        assertThat(adminRepo.searchAdmin(null, null, "", null, "es", false, null, null, null, null,
                desdeEl16, antesDel18, firstPage).getContent())
                .extracting(ProductEntity::getSlug)
                .containsExactly("cargado-el-17");

        // Solo límite inferior: del 16 en adelante.
        assertThat(adminRepo.searchAdmin(null, null, "", null, "es", false, null, null, null, null,
                desdeEl16, null, firstPage).getContent())
                .extracting(ProductEntity::getSlug)
                .containsExactlyInAnyOrder("cargado-el-17", "cargado-el-18");

        // Sin rango se ve todo, incluido lo que no tiene fecha: un filtro no aplicado no esconde nada.
        assertThat(adminRepo.searchAdmin(null, null, "", null, "es", false, null, null, null, null,
                null, null, firstPage).getContent())
                .extracting(ProductEntity::getSlug)
                .containsExactlyInAnyOrder("cargado-el-15", "cargado-el-17", "cargado-el-18",
                        "sin-fecha-de-carga");
    }

    // ── precisión del texto libre (fallback SQL) ────────────────────────────────────────

    /**
     * Los tres falsos positivos que devolvía buscar "botas", cada uno por un motivo distinto, y que aquí
     * NO deben volver:
     * <ul>
     *   <li>una falda cuya <b>descripción</b> dice "combina con botas" (la descripción no es el título);</li>
     *   <li>un blazer cuyo título <b>portugués</b> ("botao") se parecía al término español — el fuzzy solo
     *       debe aplicarse al idioma que está mirando el usuario;</li>
     *   <li>y, en cambio, sí debe seguir apareciendo lo que lleva el término en el título.</li>
     * </ul>
     */
    @Test
    void busquedaDeTextoLibre_niDescripcionesNiOtrosIdiomasCuelanFalsosPositivos() {
        ProductEntity botas = conTraduccion(baseProduct("botas-martin", "BOT-1").build(), "es",
                "Botas Martin de caña alta para mujer", "Botas de cuero con cordones");
        ProductEntity falda = conTraduccion(baseProduct("falda-plisada", "FAL-1").build(), "es",
                "Falda plisada corta para mujer", "Combina con botas altas y medias");
        ProductEntity blazer = conTraduccion(baseProduct("blazer-boton", "BLZ-1").build(), "pt",
                "Blazer curto de um botao para mulher", "Blazer curto");
        imagenEspejada(botas);
        imagenEspejada(falda);
        imagenEspejada(blazer);
        em.flush();
        em.clear();

        Pageable page = PageRequest.of(0, 20);
        List<String> encontrados = adminRepo
                .searchStorefront(ProductStatus.ACTIVE, "botas", null, null, null, null, null, null, null, null, null,
                        null, null, "es", false, true, page)
                .getContent().stream().map(ProductEntity::getSlug).toList();

        assertThat(encontrados).containsExactly("botas-martin");
    }

    /**
     * La descripción es la RED DE SEGURIDAD: cuando nada casa por título, buscar dentro del texto largo es
     * mejor que devolver una página vacía. Por eso el mismo término, con {@code wide}, sí trae la falda.
     */
    @Test
    void busquedaAmplia_siRastreaLasDescripciones() {
        ProductEntity falda = conTraduccion(baseProduct("falda-plisada", "FAL-2").build(), "es",
                "Falda plisada corta para mujer", "Combina con botas altas y medias");
        imagenEspejada(falda);
        em.flush();
        em.clear();

        Pageable page = PageRequest.of(0, 20);
        assertThat(adminRepo.searchStorefront(ProductStatus.ACTIVE, "botas", null, null, null, null, null, null, null,
                null, null, null, null, "es", true, true, page).getContent()).hasSize(1);
    }

    /** Los identificadores que manda el buscador se filtran igual: publicado y con imagen utilizable. */
    @Test
    void busquedaPorIdentificadores_respetaLaVisibilidadDelEscaparate() {
        ProductEntity visible = baseProduct("visible", "VIS-1").build();
        ProductEntity sinImagen = baseProduct("sin-imagen", "SIN-1").build();
        ProductEntity pausado = baseProduct("pausado", "PAU-1").status(ProductStatus.PAUSED).build();
        repo.save(visible);
        repo.save(sinImagen);
        repo.save(pausado);
        imagenEspejada(visible);
        imagenEspejada(pausado);
        em.flush();
        em.clear();

        List<String> encontrados = adminRepo
                .searchStorefrontByIds(ProductStatus.ACTIVE,
                        List.of(visible.getId(), sinImagen.getId(), pausado.getId()), null, null, null, null, null,
                        null, null, null)
                .stream().map(ProductEntity::getSlug).toList();

        assertThat(encontrados).containsExactly("visible");
    }

    /** Guarda un producto con su traducción (título + descripción) en el idioma dado. */
    private ProductEntity conTraduccion(ProductEntity p, String lang, String titulo, String descripcion) {
        ProductTranslationEntity t = ProductTranslationEntity.builder().product(p).language(lang).title(titulo)
                .description(descripcion).build();
        p.getTranslations().add(t);
        return repo.save(p);
    }

    /** Una imagen ya espejada a nuestro storage: sin ella el escaparate oculta el producto. */
    private void imagenEspejada(ProductEntity p) {
        em.persist(ProductImageEntity.builder().product(p).sourceUrl("https://origen/" + p.getSlug() + ".jpg")
                .cdnUrl("https://cdn/" + p.getSlug() + ".jpg").position(0).build());
    }
}
