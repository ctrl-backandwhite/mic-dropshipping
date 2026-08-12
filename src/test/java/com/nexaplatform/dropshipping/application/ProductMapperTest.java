package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.PromotionService;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.ProductMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.SupplierMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.ArrayList;

import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductMapperTest {

    private SupplierMapper supplierMapper;
    private CurrencyRateService currencyService;
    private MarginService marginService;
    private PricingService pricingService;
    private ProductMapper productMapper;

    @BeforeEach
    void setup() {
        supplierMapper = mock(SupplierMapper.class);

        // CurrencyRateService stub: pass-through (no actual conversion)
        currencyService = mock(CurrencyRateService.class);
        when(currencyService.toUsd(Mockito.any(), Mockito.anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(currencyService.usdToDisplay(Mockito.any())).thenAnswer(inv -> inv.getArgument(0));
        when(currencyService.symbolOf(Mockito.anyString())).thenReturn("$");
        when(currencyService.localeOf(Mockito.anyString())).thenReturn("en-US");

        // MarginService stub: no margin applied
        marginService = mock(MarginService.class);
        when(marginService.apply(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenAnswer(inv -> new MarginService.PriceWithMargin(inv.getArgument(0), inv.getArgument(0), null,
                        BigDecimal.ZERO));

        pricingService = new PricingService(currencyService, sinPromociones(), marginService);

        productMapper = new ProductMapper(supplierMapper, pricingService, currencyService, marginService,
                mock(com.nexaplatform.dropshipping.application.service.CustomsValuationService.class));
    }

    @Test
    void summary_picks_es_title_when_available() {
        ProductEntity p = ProductEntity.builder().source("1688").externalId("X").titleZh("中文标题")
                .basePrice(new BigDecimal("9.99")).currency("CNY").monthlySales(10).build();
        ProductTranslationEntity tr = ProductTranslationEntity.builder().product(p).language("es").title("Título")
                .build();
        p.setTranslations(new ArrayList<>());
        p.getTranslations().add(tr);
        p.setImages(new ArrayList<>());

        var view = productMapper.toSummary(p, "es");
        assertThat(view.title()).isEqualTo("Título");
        assertThat(view.basePrice()).isEqualByComparingTo("9.99");
    }

    @Test
    void summary_falls_back_to_zh_when_translation_missing() {
        ProductEntity p = ProductEntity.builder().titleZh("Fallback ZH").basePrice(new BigDecimal("1")).build();
        p.setTranslations(new ArrayList<>());
        p.setImages(new ArrayList<>());
        var view = productMapper.toSummary(p, "en");
        assertThat(view.title()).isEqualTo("Fallback ZH");
    }

    @Test
    void image_url_prefers_cdn_over_source() {
        ProductEntity p = ProductEntity.builder().titleZh("p").basePrice(new BigDecimal("1")).build();
        ProductImageEntity img = ProductImageEntity.builder().sourceUrl("https://cbu01.alicdn.com/x.jpg")
                .cdnUrl("https://cdn.nexadrop.io/x.webp").position(0).build();
        p.setImages(new ArrayList<>(java.util.List.of(img)));
        p.setTranslations(new ArrayList<>());
        var view = productMapper.toSummary(p, "es");
        assertThat(view.mainImage()).isEqualTo("https://cdn.nexadrop.io/x.webp");
    }

    @Test
    void image_url_falls_back_to_source_when_cdn_blank() {
        ProductEntity p = ProductEntity.builder().titleZh("p").basePrice(new BigDecimal("1")).build();
        ProductImageEntity img = ProductImageEntity.builder().sourceUrl("https://cbu01.alicdn.com/x.jpg").cdnUrl("")
                .position(0).build();
        p.setImages(new ArrayList<>(java.util.List.of(img)));
        p.setTranslations(new ArrayList<>());
        var view = productMapper.toSummary(p, "es");
        assertThat(view.mainImage()).isEqualTo("https://cbu01.alicdn.com/x.jpg");
    }

    /**
     * Motor de promociones que no rebaja nada: estas pruebas miden el pipeline de precio (coste →
     * margen → divisa), no las rebajas, y una promoción activa cambiaría todos los importes esperados.
     */
    private static PromotionService sinPromociones() {
        PromotionService p = mock(PromotionService.class);
        lenient().when(p.applyAutomatic(any(), any(), any())).thenAnswer(inv -> {
            java.math.BigDecimal precio = inv.getArgument(1);
            return new PromotionService.Discounted(precio, precio, java.math.BigDecimal.ZERO, null, null);
        });
        return p;
    }
}
