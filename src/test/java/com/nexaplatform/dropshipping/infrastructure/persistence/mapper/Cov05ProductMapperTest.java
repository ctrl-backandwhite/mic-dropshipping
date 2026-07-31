package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.PriceTierView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductDetailView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.VariantValueView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.VariantView;
import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.PricingService.PricedAmount;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductPriceTierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantOptionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueTranslationEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Vistas de catálogo: qué ve cada rol, cómo se resuelve el idioma del título y —lo más delicado— que
 * los tramos de precio se muestren SIEMPRE con margen aplicado. Un tramo mostrado al coste enseñaría
 * un precio y cobraría otro al pagar.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov05ProductMapperTest {

    @Mock
    SupplierMapper supplierMapper;
    @Mock
    PricingService pricingService;
    @Mock
    CurrencyRateService currencyRateService;
    @Mock
    MarginService marginService;

    @InjectMocks
    ProductMapper mapper;

    private static final PricedAmount PRICED = new PricedAmount(new BigDecimal("10.00"), new BigDecimal("25.00"),
            new BigDecimal("23.10"), "EUR", "€", "23,10 €", null, new BigDecimal("150"), new BigDecimal("20.00"),
            new BigDecimal("2.00"), new BigDecimal("3.00"), "18,50 €", "1,85 €", "2,75 €");

    @BeforeEach
    void setUp() {
        when(pricingService.priceFor(any(ProductEntity.class))).thenReturn(PRICED);
        when(pricingService.priceFor(any(ProductEntity.class), any(ProductVariantEntity.class))).thenReturn(PRICED);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("someone", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private ProductEntity product() {
        ProductEntity p = ProductEntity.builder().status(ProductStatus.ACTIVE).slug("vestido-rojo").source("1688").externalId("1").titleZh("连衣裙")
                .shortDescriptionZh("短").descriptionZh("长").basePrice(new BigDecimal("12.50")).currency("CNY")
                .build();
        p.setId(UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"));
        p.setImages(new ArrayList<>());
        p.setTranslations(new ArrayList<>());
        p.setVariants(new ArrayList<>());
        p.setVariantOptions(new ArrayList<>());
        return p;
    }

    private ProductTranslationEntity translation(ProductEntity p, String lang, String title) {
        return ProductTranslationEntity.builder().product(p).language(lang).title(title)
                .shortDescription(title + " corto").description(title + " largo").metaTitle("meta " + title)
                .metaDescription("metadesc " + title).build();
    }

    private ProductVariantEntity variant(boolean active, int stock) {
        ProductVariantEntity v = ProductVariantEntity.builder().sku("SKU").title("Rojo / M")
                .price(new BigDecimal("13.90")).stock(stock).active(active).build();
        v.setId(UUID.randomUUID());
        return v;
    }

    /* ===================== resumen de catálogo ===================== */

    @Test
    @DisplayName("un producto nulo no tiene resumen (la lista lo descarta sin reventar)")
    void unProductoNuloNoTieneResumen() {
        assertThat(mapper.toSummary(null, "es")).isNull();
    }

    @Test
    @DisplayName("las unidades disponibles suman SOLO el stock de las variantes activas")
    void lasUnidadesDisponiblesSumanSoloLasVariantesActivas() {
        ProductEntity p = product();
        p.getVariants().add(variant(true, 3));
        p.getVariants().add(variant(true, 5));
        p.getVariants().add(variant(false, 100));

        ProductSummaryView view = mapper.toSummary(p, "es");

        // Una variante desactivada no es comprable: contarla prometería stock que no se puede servir.
        assertThat(view.availableUnits()).isEqualTo(8);
    }

    @Test
    @DisplayName("si las variantes no están cargadas, el resumen sale sin unidades en vez de fallar")
    void siLasVariantesNoEstanCargadasElResumenNoFalla() {
        ProductEntity p = spy(product());
        doThrow(new IllegalStateException("lazy fuera de transacción")).when(p).getVariants();

        ProductSummaryView view = mapper.toSummary(p, "es");

        assertThat(view.availableUnits()).isNull();
        assertThat(view.title()).isEqualTo("连衣裙");
    }

    @Test
    @DisplayName("el resumen usa la primera imagen y prefiere la copia en nuestro CDN")
    void elResumenPrefiereLaImagenDelCdn() {
        ProductEntity p = product();
        p.getImages().add(ProductImageEntity.builder().position(0).sourceUrl("https://cbu01.alicdn.com/a.jpg")
                .cdnUrl("https://cdn.nexadrop.io/a.webp").build());
        p.getImages().add(ProductImageEntity.builder().position(1).sourceUrl("https://cbu01.alicdn.com/b.jpg").build());

        assertThat(mapper.toSummary(p, "es").mainImage()).isEqualTo("https://cdn.nexadrop.io/a.webp");
    }

    @Test
    @DisplayName("el resumen expone el precio ya formateado por el backend")
    void elResumenExponeElPrecioYaFormateado() {
        ProductSummaryView view = mapper.toSummary(product(), "es");

        assertThat(view.displayFormatted()).isEqualTo("23,10 €");
        assertThat(view.displayCurrency()).isEqualTo("EUR");
        assertThat(view.displayPrice()).isEqualByComparingTo("23.10");
    }

    @Test
    @DisplayName("un producto sin la marca de verificado se expone como no verificado")
    void unProductoSinMarcaDeVerificadoSaleComoNoVerificado() {
        ProductEntity p = product();
        p.setVerified(null);

        assertThat(mapper.toSummary(p, "es").verified()).isFalse();
    }

    /* ===================== idioma del título ===================== */

    @Test
    @DisplayName("el título del resumen usa el idioma pedido cuando existe")
    void elTituloDelResumenUsaElIdiomaPedido() {
        ProductEntity p = product();
        p.getTranslations().add(translation(p, "es", "Vestido rojo"));
        p.getTranslations().add(translation(p, "en", "Red dress"));

        assertThat(mapper.toSummary(p, "es").title()).isEqualTo("Vestido rojo");
    }

    @Test
    @DisplayName("sin traducción propia, el título del resumen cae a inglés antes que al chino")
    void elTituloDelResumenCaeAInglesAntesQueAlChino() {
        ProductEntity p = product();
        p.getTranslations().add(translation(p, "en", "Red dress"));

        assertThat(mapper.toSummary(p, "fr").title()).isEqualTo("Red dress");
    }

    @Test
    @DisplayName("una traducción con el título vacío no cuenta como traducción")
    void unaTraduccionConTituloVacioNoCuenta() {
        ProductEntity p = product();
        p.getTranslations().add(translation(p, "es", "  "));

        assertThat(mapper.toSummary(p, "es").title()).isEqualTo("连衣裙");
    }

    /* ===================== ficha de producto ===================== */

    @Test
    @DisplayName("la ficha NO enseña coste, margen ni desglose a quien no es admin")
    void laFichaOcultaCosteYMargenAQuienNoEsAdmin() {
        authenticateAs("OPERATOR");
        ProductEntity p = product();

        ProductDetailView view = mapper.toDetail(p, "es", List.of());

        assertThat(view.costUsd()).isNull();
        assertThat(view.retailUsd()).isNull();
        assertThat(view.appliedMarginPercent()).isNull();
        assertThat(view.baseFormatted()).isNull();
        assertThat(view.ivaFormatted()).isNull();
        assertThat(view.shippingFormatted()).isNull();
        // El precio de venta sí lo ve todo el mundo.
        assertThat(view.displayFormatted()).isEqualTo("23,10 €");
    }

    @Test
    @DisplayName("la ficha enseña coste, margen y desglose SOLO al admin")
    void laFichaEnsenaCosteYDesgloseSoloAlAdmin() {
        authenticateAs("ADMIN");

        ProductDetailView view = mapper.toDetail(product(), "es", List.of());

        assertThat(view.costUsd()).isEqualByComparingTo("10.00");
        assertThat(view.retailUsd()).isEqualByComparingTo("25.00");
        assertThat(view.appliedMarginPercent()).isEqualByComparingTo("150");
        assertThat(view.baseFormatted()).isEqualTo("18,50 €");
        assertThat(view.ivaFormatted()).isEqualTo("1,85 €");
        assertThat(view.shippingFormatted()).isEqualTo("2,75 €");
    }

    @Test
    @DisplayName("sin sesión iniciada tampoco se filtra el coste")
    void sinSesionIniciadaTampocoSeFiltraElCoste() {
        assertThat(mapper.toDetail(product(), "es", List.of()).costUsd()).isNull();
    }

    @Test
    @DisplayName("la ficha nunca muestra el chino si existe alguna traducción")
    void laFichaNuncaMuestraElChinoSiHayAlgunaTraduccion() {
        ProductEntity p = product();
        p.getTranslations().add(translation(p, "pt", "Vestido vermelho"));

        ProductDetailView view = mapper.toDetail(p, "fr", List.of());

        // fr no existe y en tampoco: se usa la primera traducción con título, jamás el chino canónico.
        assertThat(view.title()).isEqualTo("Vestido vermelho");
        assertThat(view.titleZh()).isEqualTo("连衣裙");
    }

    @Test
    @DisplayName("la ficha cae a inglés antes que a cualquier otro idioma")
    void laFichaCaeAInglesAntesQueACualquierOtro() {
        ProductEntity p = product();
        p.getTranslations().add(translation(p, "pt", "Vestido vermelho"));
        p.getTranslations().add(translation(p, "en", "Red dress"));

        assertThat(mapper.toDetail(p, "de", List.of()).title()).isEqualTo("Red dress");
    }

    @Test
    @DisplayName("sin ninguna traducción la ficha cae al texto chino de origen")
    void sinNingunaTraduccionLaFichaCaeAlChino() {
        ProductDetailView view = mapper.toDetail(product(), "es", List.of());

        assertThat(view.title()).isEqualTo("连衣裙");
        assertThat(view.shortDescription()).isEqualTo("短");
        assertThat(view.description()).isEqualTo("长");
        assertThat(view.metaTitle()).isNull();
    }

    @Test
    @DisplayName("una ficha sin tramos de precio devuelve la lista vacía, no nula")
    void unaFichaSinTramosDevuelveListaVacia() {
        assertThat(mapper.toDetail(product(), "es", null).priceTiers()).isEmpty();
    }

    /* ===================== variantes ===================== */

    @Test
    @DisplayName("el peso de la variante usa el del bulto cuando existe (es lo que se factura al enviar)")
    void elPesoDeLaVarianteUsaElDelBulto() {
        ProductVariantEntity v = variant(true, 5);
        v.setWeightGrams(300);
        v.setPackageWeightGrams(520);

        assertThat(mapper.toVariantView(v).weightGrams()).isEqualTo(520);
    }

    @Test
    @DisplayName("un peso de bulto a cero no sustituye al peso neto")
    void unPesoDeBultoACeroNoSustituyeAlNeto() {
        ProductVariantEntity v = variant(true, 5);
        v.setWeightGrams(300);
        v.setPackageWeightGrams(0);

        assertThat(mapper.toVariantView(v).weightGrams()).isEqualTo(300);
    }

    @Test
    @DisplayName("la imagen de la variante prefiere la copia espejada en nuestro CDN")
    void laImagenDeLaVariantePrefiereElCdn() {
        ProductVariantEntity v = variant(true, 5);
        v.setImageSourceUrl("https://cbu01.alicdn.com/v.jpg");
        v.setImageCdnUrl("https://cdn.nexadrop.io/v.webp");

        assertThat(mapper.toVariantView(v).imageUrl()).isEqualTo("https://cdn.nexadrop.io/v.webp");
    }

    @Test
    @DisplayName("sin copia espejada se muestra la imagen original del proveedor")
    void sinCopiaEspejadaSeMuestraLaOriginal() {
        ProductVariantEntity v = variant(true, 5);
        v.setImageSourceUrl("https://cbu01.alicdn.com/v.jpg");
        v.setImageCdnUrl("   ");

        assertThat(mapper.toVariantView(v).imageUrl()).isEqualTo("https://cbu01.alicdn.com/v.jpg");
    }

    @Test
    @DisplayName("la variante de la ficha lleva el precio en la divisa del usuario, no el del proveedor")
    void laVarianteDeLaFichaLlevaElPrecioEnDivisaDelUsuario() {
        ProductEntity p = product();
        ProductVariantEntity v = variant(true, 5);

        VariantView view = mapper.toVariantView(p, v);

        assertThat(view.price()).isEqualByComparingTo("23.10");
        assertThat(view.priceFormatted()).isEqualTo("23,10 €");
    }

    @Test
    @DisplayName("el valor de variante usa la traducción del idioma pedido, sin importar mayúsculas")
    void elValorDeVarianteUsaLaTraduccionDelIdiomaPedido() {
        VariantValueEntity value = VariantValueEntity.builder().valueZh("红色").value("Rojo (neutro)").position(0)
                .translations(new ArrayList<>()).build();
        value.setId(UUID.randomUUID());
        value.getTranslations().add(VariantValueTranslationEntity.builder().language("ES").value("Rojo").build());
        value.getTranslations().add(VariantValueTranslationEntity.builder().language("en").value("Red").build());

        VariantValueView view = mapper.toValueView(value, "es");

        assertThat(view.valueLocalized()).isEqualTo("Rojo");
        assertThat(view.translations()).containsEntry("es", "Rojo").containsEntry("en", "Red");
    }

    @Test
    @DisplayName("sin traducción del idioma pedido, el valor cae al override neutral")
    void sinTraduccionElValorCaeAlOverrideNeutral() {
        VariantValueEntity value = VariantValueEntity.builder().valueZh("红色").value("Rojo (neutro)")
                .translations(new ArrayList<>()).build();
        value.getTranslations().add(VariantValueTranslationEntity.builder().language("en").value("Red").build());

        assertThat(mapper.toValueView(value, "fr").valueLocalized()).isEqualTo("Rojo (neutro)");
    }

    @Test
    @DisplayName("una traducción sin idioma o sin texto no entra en el mapa de traducciones")
    void unaTraduccionIncompletaNoEntraEnElMapa() {
        VariantValueEntity value = VariantValueEntity.builder().valueZh("红色").translations(new ArrayList<>()).build();
        value.getTranslations().add(VariantValueTranslationEntity.builder().language(null).value("Sin idioma").build());
        value.getTranslations().add(VariantValueTranslationEntity.builder().language("it").value(null).build());

        VariantValueView view = mapper.toValueView(value, "es");

        assertThat(view.translations()).isEmpty();
        assertThat(view.valueLocalized()).isNull();
    }

    @Test
    @DisplayName("la opción sin idioma no resuelve traducciones (compatibilidad heredada)")
    void laOpcionSinIdiomaNoResuelveTraducciones() {
        VariantValueEntity value = VariantValueEntity.builder().valueZh("红色").value("Rojo (neutro)").position(0)
                .translations(new ArrayList<>()).build();
        value.getTranslations().add(VariantValueTranslationEntity.builder().language("es").value("Rojo").build());
        VariantOptionEntity option = VariantOptionEntity.builder().nameZh("颜色").name("Color").position(0)
                .values(new ArrayList<>(List.of(value))).build();

        assertThat(mapper.toOptionView(option).values().get(0).valueLocalized()).isEqualTo("Rojo (neutro)");
    }

    /* ===================== tramos de precio ===================== */

    @Test
    @DisplayName("el tramo de precio sale de la MISMA cuenta que el precio que se cobra")
    void elTramoDePrecioSeTarificaComoElPrecioQueSeCobra() {
        // El tramo tenía su propia fórmula (coste → USD → margen) y se quedaba ahí, sin el IVA ni el
        // envío que sí lleva el precio real: la ficha anunciaba «2+ → 1,99 $» y se cobraban 3,57 $.
        // Ahora pasa por PricingService, que es quien tarifica también la ficha, la variante y el pedido.
        ProductEntity p = product();
        ProductPriceTierEntity tier = ProductPriceTierEntity.builder().product(p).minQty(10).maxQty(49)
                .unitPrice(new BigDecimal("14.13")).currency("CNY").build();
        when(pricingService.priceForSupplierAmount(p, null, new BigDecimal("14.13")))
                .thenReturn(precio(new BigDecimal("28.26"), "EUR", "28,26 €"));

        PriceTierView view = mapper.toPriceTierView(tier);

        assertThat(view.unitPrice()).isEqualByComparingTo("28.26");
        assertThat(view.unitPriceFormatted()).isEqualTo("28,26 €");
        assertThat(view.currency()).isEqualTo("EUR");
        assertThat(view.minQty()).isEqualTo(10);
        assertThat(view.maxQty()).isEqualTo(49);
    }

    /** PricedAmount con lo único que lee la vista del tramo: importe, divisa y texto formateado. */
    private static PricedAmount precio(BigDecimal importe, String divisa, String formateado) {
        return new PricedAmount(null, null, importe, divisa, "€", formateado, null, null,
                null, null, null, null, null, null);
    }

    @Test
    @DisplayName("el importe del tramo se tarifica con el producto al que pertenece")
    void elTramoSeTarificaConSuProducto() {
        // El IVA y el envío son del producto, así que el tramo tiene que ir acompañado del suyo: con
        // otro producto saldrían los impuestos de otro país o un envío que no es el de esta pieza.
        ProductEntity p = product();
        ProductPriceTierEntity tier = ProductPriceTierEntity.builder().product(p).minQty(1)
                .unitPrice(new BigDecimal("9.00")).currency(null).build();
        when(pricingService.priceForSupplierAmount(any(), any(), any()))
                .thenReturn(precio(new BigDecimal("18.00"), "EUR", "18,00 €"));

        mapper.toPriceTierView(tier);

        verify(pricingService).priceForSupplierAmount(p, null, new BigDecimal("9.00"));
    }

    @Test
    @DisplayName("si no se puede tarificar, el tramo usa la divisa de display en vez de quedarse sin ella")
    void unTramoSinTarifaConservaLaDivisaDeDisplay() {
        ProductEntity p = product();
        ProductPriceTierEntity tier = ProductPriceTierEntity.builder().product(p).minQty(1)
                .unitPrice(new BigDecimal("7.00")).currency("CNY").build();
        when(pricingService.priceForSupplierAmount(any(), any(), any()))
                .thenReturn(precio(null, null, null));
        when(pricingService.displayCurrencyCode()).thenReturn("EUR");
        when(currencyRateService.formatDisplay(null, "EUR")).thenReturn("—");

        PriceTierView view = mapper.toPriceTierView(tier);

        assertThat(view.unitPrice()).isNull();
        assertThat(view.currency()).isEqualTo("EUR");
    }

    /* ===================== imágenes ===================== */

    @Test
    @DisplayName("la vista de imagen conserva posición, rol y ambas URLs")
    void laVistaDeImagenConservaPosicionRolYUrls() {
        ProductImageEntity img = ProductImageEntity.builder().position(2).role("GALLERY")
                .sourceUrl("https://cbu01.alicdn.com/a.jpg").cdnUrl("https://cdn.nexadrop.io/a.webp").build();

        assertThat(mapper.toImageView(img).position()).isEqualTo(2);
        assertThat(mapper.toImageView(img).role()).isEqualTo("GALLERY");
        assertThat(mapper.toImageView(img).sourceUrl()).isEqualTo("https://cbu01.alicdn.com/a.jpg");
        assertThat(mapper.toImageView(img).cdnUrl()).isEqualTo("https://cdn.nexadrop.io/a.webp");
    }
}
