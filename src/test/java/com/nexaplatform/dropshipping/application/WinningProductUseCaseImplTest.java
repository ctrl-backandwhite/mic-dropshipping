package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.usecase.impl.WinningProductUseCaseImpl;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.domain.model.WinningProduct;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WinningProductUseCaseImplTest {

    @Mock
    ProductRepository productRepository;
    @InjectMocks
    WinningProductUseCaseImpl useCase;

    // ── helpers ──────────────────────────────────────────────────────────────

    private static ProductEntity product(String slug, ProductStatus status, int monthlySales, String trendScore) {
        ProductEntity p = ProductEntity.builder().slug(slug).titleZh(slug + "-zh").status(status)
                .monthlySales(monthlySales)
                .trendScore(trendScore == null ? null : new BigDecimal(trendScore))
                .basePrice(new BigDecimal("9.99")).build();
        p.setId(UUID.randomUUID());
        return p;
    }

    private static void withCategory(ProductEntity p, UUID categoryId) {
        CategoryEntity c = CategoryEntity.builder().slug("cat-" + categoryId).build();
        c.setId(categoryId);
        p.setCategory(c);
    }

    private static void withTranslation(ProductEntity p, String lang, String title) {
        ProductTranslationEntity t = ProductTranslationEntity.builder().language(lang).title(title).build();
        List<ProductTranslationEntity> list = new ArrayList<>(p.getTranslations());
        list.add(t);
        p.setTranslations(list);
    }

    private static void withImage(ProductEntity p, String cdnUrl, String sourceUrl) {
        ProductImageEntity img = ProductImageEntity.builder().cdnUrl(cdnUrl).sourceUrl(sourceUrl).build();
        List<ProductImageEntity> list = new ArrayList<>(p.getImages());
        list.add(img);
        p.setImages(list);
    }

    // ── salesTrends: ranking, filtros, límite ─────────────────────────────────

    @Test
    void salesTrends_ordersByMonthlySalesDescAndFiltersOutNonActive() {
        ProductEntity low = product("low", ProductStatus.ACTIVE, 10, null);
        ProductEntity high = product("high", ProductStatus.ACTIVE, 90, null);
        ProductEntity mid = product("mid", ProductStatus.ACTIVE, 50, null);
        ProductEntity draft = product("draft", ProductStatus.DRAFT, 999, null);
        when(productRepository.findAll()).thenReturn(List.of(low, high, mid, draft));

        List<WinningProduct> result = useCase.salesTrends(null, 100, "en");

        // El DRAFT queda fuera; el resto ordenado por ventas mensuales descendente.
        assertThat(result).extracting(WinningProduct::getSlug).containsExactly("high", "mid", "low");
        assertThat(result).extracting(WinningProduct::getMonthlySales).containsExactly(90, 50, 10);
    }

    @Test
    void salesTrends_filtersByCategoryWhenProvided() {
        UUID wanted = UUID.randomUUID();
        ProductEntity inCat = product("in", ProductStatus.ACTIVE, 30, null);
        withCategory(inCat, wanted);
        ProductEntity otherCat = product("other", ProductStatus.ACTIVE, 80, null);
        withCategory(otherCat, UUID.randomUUID());
        ProductEntity noCat = product("none", ProductStatus.ACTIVE, 70, null);
        when(productRepository.findAll()).thenReturn(List.of(inCat, otherCat, noCat));

        List<WinningProduct> result = useCase.salesTrends(wanted, 100, "en");

        assertThat(result).extracting(WinningProduct::getSlug).containsExactly("in");
    }

    @Test
    void salesTrends_respectsLimit() {
        List<ProductEntity> many = IntStream.range(0, 10)
                .mapToObj(i -> product("p" + i, ProductStatus.ACTIVE, 100 - i, null)).toList();
        when(productRepository.findAll()).thenReturn(many);

        List<WinningProduct> result = useCase.salesTrends(null, 3, "en");

        assertThat(result).hasSize(3);
        assertThat(result).extracting(WinningProduct::getSlug).containsExactly("p0", "p1", "p2");
    }

    @Test
    void salesTrends_hardCapsAtOneHundred() {
        List<ProductEntity> many = IntStream.range(0, 150)
                .mapToObj(i -> product("p" + i, ProductStatus.ACTIVE, 1000 - i, null)).toList();
        when(productRepository.findAll()).thenReturn(many);

        List<WinningProduct> result = useCase.salesTrends(null, 9999, "en");

        assertThat(result).hasSize(100);
    }

    // ── winning: ranking por trendScore, null-safe ────────────────────────────

    @Test
    void winning_ordersByTrendScoreDescTreatingNullAsZero() {
        ProductEntity top = product("top", ProductStatus.ACTIVE, 0, "8.5");
        ProductEntity midScore = product("mid", ProductStatus.ACTIVE, 0, "2.0");
        ProductEntity nullScore = product("null", ProductStatus.ACTIVE, 0, null);
        ProductEntity paused = product("paused", ProductStatus.PAUSED, 0, "99");
        when(productRepository.findAll()).thenReturn(List.of(nullScore, top, midScore, paused));

        List<WinningProduct> result = useCase.winning(100, "en");

        // PAUSED descartado; null tratado como 0 → último.
        assertThat(result).extracting(WinningProduct::getSlug).containsExactly("top", "mid", "null");
        assertThat(result.get(2).getTrendScore()).isNull();
    }

    @Test
    void winning_respectsLimit() {
        List<ProductEntity> many = IntStream.range(0, 20)
                .mapToObj(i -> product("p" + i, ProductStatus.ACTIVE, 0, String.valueOf(100 - i))).toList();
        when(productRepository.findAll()).thenReturn(many);

        List<WinningProduct> result = useCase.winning(5, "en");

        assertThat(result).hasSize(5);
        assertThat(result).extracting(WinningProduct::getSlug).containsExactly("p0", "p1", "p2", "p3", "p4");
    }

    // ── toWin mapping: título por idioma, imagen, precio ─────────────────────

    @Test
    void mapping_prefersRequestedLanguageTitle() {
        ProductEntity p = product("slug", ProductStatus.ACTIVE, 5, "1");
        withTranslation(p, "en", "English title");
        withTranslation(p, "es", "Titulo espanol");
        when(productRepository.findAll()).thenReturn(List.of(p));

        List<WinningProduct> result = useCase.salesTrends(null, 10, "es");

        assertThat(result.get(0).getTitle()).isEqualTo("Titulo espanol");
        assertThat(result.get(0).getPrice()).isEqualByComparingTo("9.99");
    }

    @Test
    void mapping_fallsBackToEnglishThenChineseTitle() {
        ProductEntity onlyEn = product("en-only", ProductStatus.ACTIVE, 9, "1");
        withTranslation(onlyEn, "en", "English title");
        ProductEntity noTranslations = product("zh-only", ProductStatus.ACTIVE, 8, "1");
        when(productRepository.findAll()).thenReturn(List.of(onlyEn, noTranslations));

        // Pide "fr": no existe → cae a inglés; el segundo no tiene traducciones → titleZh.
        List<WinningProduct> result = useCase.salesTrends(null, 10, "fr");

        assertThat(result).extracting(WinningProduct::getSlug, WinningProduct::getTitle)
                .containsExactly(tuple("en-only", "English title"), tuple("zh-only", "zh-only-zh"));
    }

    @Test
    void mapping_picksCdnUrlWhenPresentElseSourceUrl() {
        ProductEntity cdn = product("cdn", ProductStatus.ACTIVE, 9, "1");
        withImage(cdn, "https://cdn/img.jpg", "https://src/img.jpg");
        ProductEntity blankCdn = product("src", ProductStatus.ACTIVE, 8, "1");
        withImage(blankCdn, "  ", "https://src/only.jpg");
        ProductEntity noImage = product("noimg", ProductStatus.ACTIVE, 7, "1");
        when(productRepository.findAll()).thenReturn(List.of(cdn, blankCdn, noImage));

        List<WinningProduct> result = useCase.salesTrends(null, 10, "en");

        assertThat(result).extracting(WinningProduct::getSlug, WinningProduct::getMainImage).containsExactly(
                tuple("cdn", "https://cdn/img.jpg"),
                tuple("src", "https://src/only.jpg"),
                tuple("noimg", null));
    }
}
