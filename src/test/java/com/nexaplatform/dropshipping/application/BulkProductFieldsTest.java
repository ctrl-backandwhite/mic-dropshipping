package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn;
import com.nexaplatform.dropshipping.application.service.BulkProductFields;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantOptionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueTranslationEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Volcado de una fila de carga sobre un producto que ya existe.
 *
 * <p>La regla que atraviesa todo: <b>lo que la fila no trae, no se toca</b>. Un {@code null} significa
 * "este import no habla de ese campo", no "bórralo". El importador hace UPSERT, así que una reimportación
 * parcial —la que sólo rellena pesos, por ejemplo— no puede llevarse por delante la partida arancelaria
 * ni el vídeo que ya estaban puestos. Sin estos tests, ese borrado silencioso no lo nota nadie hasta que
 * un envío se queda sin declarar.
 */
class BulkProductFieldsTest {

    /** Producto ya cargado, con datos que una reimportación parcial NO debe perder. */
    private static ProductEntity existing() {
        ProductEntity p = new ProductEntity();
        p.setPackageWeightGrams(500);
        p.setLengthMm(300);
        p.setWidthMm(200);
        p.setHeightMm(100);
        p.setHsCode("6109100000");
        p.setCountryOfOrigin("CN");
        p.setCustomsMaterial("Algodón");
        p.setCustomsUsage("Uso diario");
        p.setBatteryType("NONE");
        p.setShipFrom("Shenzhen");
        p.setLeadTimeDays(7);
        p.setVideoUrl("https://cdn/video.mp4");
        p.setHasVideo(true);
        p.setRating(new BigDecimal("4.50"));
        p.setReviewCount(120);
        return p;
    }

    private static BulkProductDtoIn emptyRow() {
        return new BulkProductDtoIn();
    }

    // ---------------------------------------------------------------- lo que no viene, no se toca

    @Test
    void unaFilaVaciaNoBorraNingunDatoYaCargado() {
        ProductEntity p = existing();
        BulkProductDtoIn r = emptyRow();

        BulkProductFields.applyPackageDimensions(p, r);
        BulkProductFields.applyCustomsFields(p, r);
        BulkProductFields.applyCommercialFields(p, r);
        BulkProductFields.applyRatingBreakdown(p, r);

        assertThat(p.getPackageWeightGrams()).isEqualTo(500);
        assertThat(p.getHsCode()).isEqualTo("6109100000");
        assertThat(p.getCustomsMaterial()).isEqualTo("Algodón");
        assertThat(p.getShipFrom()).isEqualTo("Shenzhen");
        assertThat(p.getVideoUrl()).isEqualTo("https://cdn/video.mp4");
        assertThat(p.getRating()).isEqualByComparingTo("4.50");
        assertThat(p.getReviewCount()).isEqualTo(120);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void unTextoEnBlancoCuentaComoAusenteYNoPisaElDatoBueno(String blank) {
        // Un CSV mal exportado llena las columnas vacías con "": tomarlo por bueno dejaría el producto
        // sin partida arancelaria y el envío sin poder declararse.
        ProductEntity p = existing();
        BulkProductDtoIn r = emptyRow();
        r.setHsCode(blank);
        r.setCountryOfOrigin(blank);
        r.setCustomsMaterial(blank);
        r.setShipFrom(blank);
        r.setVideoUrl(blank);

        BulkProductFields.applyCustomsFields(p, r);
        BulkProductFields.applyCommercialFields(p, r);

        assertThat(p.getHsCode()).isEqualTo("6109100000");
        assertThat(p.getCountryOfOrigin()).isEqualTo("CN");
        assertThat(p.getShipFrom()).isEqualTo("Shenzhen");
        assertThat(p.getVideoUrl()).isEqualTo("https://cdn/video.mp4");
    }

    @Test
    void unaListaVaciaTampocoPisaLaQueYaHabia() {
        ProductEntity p = existing();
        p.setCertifications(List.of("CE"));
        BulkProductDtoIn r = emptyRow();
        r.setCertifications(List.of());
        r.setVideoUrls(List.of());

        BulkProductFields.applyCommercialFields(p, r);

        assertThat(p.getCertifications()).containsExactly("CE");
    }

    // ---------------------------------------------------------------- lo que sí viene, se actualiza

    @Test
    void lasMedidasQueLleganSustituyenALasAnteriores() {
        ProductEntity p = existing();
        BulkProductDtoIn r = emptyRow();
        r.setPackageWeightGrams(320);
        r.setLengthMm(250);

        BulkProductFields.applyPackageDimensions(p, r);

        assertThat(p.getPackageWeightGrams()).isEqualTo(320);
        assertThat(p.getLengthMm()).isEqualTo(250);
        assertThat(p.getWidthMm()).isEqualTo(200);      // no venía: se conserva
    }

    @Test
    void elTipoDeBateriaSeNormalizaAMayusculasPorqueSeComparaConConstantes() {
        // De ese valor depende que el bulto vaya por un canal restringido o no.
        ProductEntity p = existing();
        BulkProductDtoIn r = emptyRow();
        r.setBatteryType("  li-ion  ");

        BulkProductFields.applyCustomsFields(p, r);

        assertThat(p.getBatteryType()).isEqualTo("LI-ION");
    }

    @Test
    void unVideoMarcaElProductoComoQueTieneVideo() {
        ProductEntity p = new ProductEntity();
        BulkProductDtoIn r = emptyRow();
        r.setVideoUrl("https://cdn/demo.mp4");

        BulkProductFields.applyCommercialFields(p, r);

        assertThat(p.getVideoUrl()).isEqualTo("https://cdn/demo.mp4");
        assertThat(p.getHasVideo()).isTrue();
    }

    @Test
    void unaListaDeVideosTambienMarcaElProductoComoQueTieneVideo() {
        ProductEntity p = new ProductEntity();
        BulkProductDtoIn r = emptyRow();
        r.setVideoUrls(List.of("https://cdn/a.mp4", "https://cdn/b.mp4"));

        BulkProductFields.applyCommercialFields(p, r);

        assertThat(p.getHasVideo()).isTrue();
        assertThat(p.getVideoUrls()).hasSize(2);
    }

    // ---------------------------------------------------------------- desglose de reseñas

    @Test
    void elNumeroDeResenasEsLaSumaDelDesgloseYLaMediaSaleDeEl() {
        // 10×5 + 5×4 + 1×3 = 73 sobre 16 reseñas = 4,5625 -> 4,56
        ProductEntity p = new ProductEntity();
        BulkProductDtoIn r = emptyRow();
        r.setRatingBreakdown(new LinkedHashMap<>(Map.of("5", 10, "4", 5, "3", 1)));

        BulkProductFields.applyRatingBreakdown(p, r);

        assertThat(p.getReviewCount()).isEqualTo(16);
        assertThat(p.getRating()).isEqualByComparingTo("4.56");
    }

    @Test
    void siElProveedorDeclaraSuMediaNoSeSobrescribeConLaCalculada() {
        // La media declarada es el dato del proveedor; la derivada es una aproximación nuestra.
        ProductEntity p = new ProductEntity();
        BulkProductDtoIn r = emptyRow();
        r.setRating(new BigDecimal("4.90"));
        r.setRatingBreakdown(new LinkedHashMap<>(Map.of("5", 10, "1", 10)));

        BulkProductFields.applyRatingBreakdown(p, r);

        assertThat(p.getReviewCount()).isEqualTo(20);
        assertThat(p.getRating()).isNull();     // lo pone el volcado general, no este bloque
    }

    @Test
    void unaClaveQueNoEsUnNumeroSeIgnoraSinTumbarLaFila() {
        // Las claves llegan como texto desde JSON y a veces traen basura; tumbar la importación entera
        // por una clave rara costaría el lote completo.
        ProductEntity p = new ProductEntity();
        BulkProductDtoIn r = emptyRow();
        Map<String, Integer> breakdown = new LinkedHashMap<>();
        breakdown.put("5", 10);
        breakdown.put("cinco estrellas", 3);
        breakdown.put("4", 2);

        assertThatCode(() -> BulkProductFields.applyRatingBreakdown(p, r)).doesNotThrowAnyException();

        r.setRatingBreakdown(breakdown);
        BulkProductFields.applyRatingBreakdown(p, r);

        assertThat(p.getReviewCount()).isEqualTo(12);   // sólo las claves numéricas
        assertThat(p.getRating()).isEqualByComparingTo("4.83");
    }

    @Test
    void unConteoNuloDentroDelDesgloseCuentaComoCero() {
        ProductEntity p = new ProductEntity();
        BulkProductDtoIn r = emptyRow();
        Map<String, Integer> breakdown = new LinkedHashMap<>();
        breakdown.put("5", 4);
        breakdown.put("1", null);

        r.setRatingBreakdown(breakdown);
        BulkProductFields.applyRatingBreakdown(p, r);

        assertThat(p.getReviewCount()).isEqualTo(4);
        assertThat(p.getRating()).isEqualByComparingTo("5.00");
    }

    @Test
    void unDesgloseTodoAceroNoInventaUnaMedia() {
        // Dividir entre cero reventaría; y una media inventada engañaría al comprador.
        ProductEntity p = new ProductEntity();
        BulkProductDtoIn r = emptyRow();
        r.setRatingBreakdown(new LinkedHashMap<>(Map.of("5", 0, "4", 0)));

        BulkProductFields.applyRatingBreakdown(p, r);

        assertThat(p.getReviewCount()).isZero();
        assertThat(p.getRating()).isNull();
    }

    @Test
    void sinDesgloseElProductoConservaSusResenasAnteriores() {
        ProductEntity p = existing();
        BulkProductDtoIn r = emptyRow();
        r.setRatingBreakdown(Map.of());

        BulkProductFields.applyRatingBreakdown(p, r);

        assertThat(p.getReviewCount()).isEqualTo(120);
        assertThat(p.getRating()).isEqualByComparingTo("4.50");
    }

    // ---------------------------------------------------------------- logística por variante

    @Test
    void elPesoYLasMedidasSeAplicanALaVarianteQueCoincidePorSku() {
        // Es lo que permite que la reimportación de la "báscula" actualice cada talla sin tocar el resto.
        ProductEntity p = new ProductEntity();
        VariantEntityPair pair = variants("SKU-M", "SKU-L");
        p.setVariants(List.of(pair.a(), pair.b()));

        BulkProductDtoIn r = emptyRow();
        r.setVariants(List.of(variantRow("SKU-M", 300, 250, "SKU-PROV-M")));

        BulkProductFields.applyVariantLogistics(p, r);

        assertThat(pair.a().getPackageWeightGrams()).isEqualTo(300);
        assertThat(pair.a().getLengthMm()).isEqualTo(250);
        assertThat(pair.a().getSupplierSkuId()).isEqualTo("SKU-PROV-M");
        assertThat(pair.b().getPackageWeightGrams()).isNull();      // la otra talla no se toca
    }

    @Test
    void unaVarianteDeLaFilaQueNoCasaConNingunSkuSeIgnoraSinRomper() {
        ProductEntity p = new ProductEntity();
        VariantEntityPair pair = variants("SKU-M", "SKU-L");
        p.setVariants(List.of(pair.a(), pair.b()));

        BulkProductDtoIn r = emptyRow();
        r.setVariants(List.of(variantRow("SKU-QUE-NO-EXISTE", 300, 250, "X")));

        assertThatCode(() -> BulkProductFields.applyVariantLogistics(p, r)).doesNotThrowAnyException();
        assertThat(pair.a().getPackageWeightGrams()).isNull();
    }

    @Test
    void unaFilaSinVariantesNoTocaLasDelProducto() {
        ProductEntity p = new ProductEntity();
        VariantEntityPair pair = variants("SKU-M", "SKU-L");
        pair.a().setPackageWeightGrams(400);
        p.setVariants(List.of(pair.a(), pair.b()));

        BulkProductFields.applyVariantLogistics(p, emptyRow());

        assertThat(pair.a().getPackageWeightGrams()).isEqualTo(400);
    }

    // ---------------------------------------------------------------- traducciones extra

    @Test
    void unIdiomaNuevoSeAnadeYUnoExistenteSeActualizaEnVezDeDuplicarse() {
        // El catálogo se reimporta a menudo: acumular traducciones dejaría la vieja conviviendo con la nueva.
        ProductEntity p = new ProductEntity();
        p.setTranslations(new java.util.ArrayList<>(List.of(
                translationEntity("fr", "Ancien titre"))));

        BulkProductDtoIn r = emptyRow();
        r.setTranslations(new LinkedHashMap<>(Map.of(
                "FR", translationRow("Montre homme", "Description FR"),
                "de", translationRow("Herrenuhr", "Beschreibung"))));

        BulkProductFields.applyExtraTranslations(p, r);

        assertThat(p.getTranslations()).hasSize(2);
        assertThat(p.getTranslations()).anyMatch(t -> "fr".equals(t.getLanguage())
                && "Montre homme".equals(t.getTitle()));
        assertThat(p.getTranslations()).anyMatch(t -> "de".equals(t.getLanguage()));
    }

    @Test
    void unaTraduccionSinTituloSeIgnoraPorqueDejariaElProductoSinNombreEnEseIdioma() {
        ProductEntity p = new ProductEntity();
        p.setTranslations(new java.util.ArrayList<>());

        BulkProductDtoIn r = emptyRow();
        r.setTranslations(new LinkedHashMap<>(Map.of("it", translationRow("   ", "Descrizione"))));

        BulkProductFields.applyExtraTranslations(p, r);

        assertThat(p.getTranslations()).isEmpty();
    }

    @Test
    void unaDescripcionCortaDemasiadoLargaSeCapaEnVezDeTumbarLaFila() {
        // La columna admite 2000 caracteres; una descripción larga de 1688 la desbordaba.
        ProductEntity p = new ProductEntity();
        p.setTranslations(new java.util.ArrayList<>());

        BulkProductDtoIn r = emptyRow();
        r.setTranslations(new LinkedHashMap<>(Map.of("nl", translationRow("Titel", "x".repeat(5000)))));

        BulkProductFields.applyExtraTranslations(p, r);

        assertThat(p.getTranslations().get(0).getShortDescription())
                .hasSize(BulkProductFields.MAX_SHORT_DESCRIPTION);
    }

    @Test
    void sinDescripcionSeUsaElTituloParaNoDejarElCampoVacio() {
        ProductEntity p = new ProductEntity();
        p.setTranslations(new java.util.ArrayList<>());

        BulkProductDtoIn r = emptyRow();
        r.setTranslations(new LinkedHashMap<>(Map.of("pt", translationRow("Relógio", null))));

        BulkProductFields.applyExtraTranslations(p, r);

        assertThat(p.getTranslations().get(0).getShortDescription()).isEqualTo("Relógio");
    }

    // ---------------------------------------------------------------- utilidades del test

    private record VariantEntityPair(ProductVariantEntity a, ProductVariantEntity b) {
    }

    private static VariantEntityPair variants(String skuA, String skuB) {
        ProductVariantEntity a = new ProductVariantEntity();
        a.setSku(skuA);
        ProductVariantEntity b = new ProductVariantEntity();
        b.setSku(skuB);
        return new VariantEntityPair(a, b);
    }

    private static BulkProductDtoIn.BulkVariant variantRow(String sku, Integer packageWeight, Integer lengthMm,
            String supplierSkuId) {
        BulkProductDtoIn.BulkVariant v = new BulkProductDtoIn.BulkVariant();
        v.setSku(sku);
        v.setPackageWeightGrams(packageWeight);
        v.setLengthMm(lengthMm);
        v.setSupplierSkuId(supplierSkuId);
        return v;
    }

    private static ProductTranslationEntity translationEntity(String lang, String title) {
        return ProductTranslationEntity.builder().language(lang).title(title).build();
    }

    private static BulkProductDtoIn.BulkTranslation translationRow(String title, String description) {
        BulkProductDtoIn.BulkTranslation t = new BulkProductDtoIn.BulkTranslation();
        t.setTitle(title);
        t.setDescription(description);
        return t;
    }

    // ---------------------------------------------------------------- traducciones de los ejes

    @Test
    void lasTraduccionesDelValorSeEmparejanPorSuTextoEnChino() {
        // El chino es la clave estable que viene del proveedor; el texto traducido cambia entre cargas.
        ProductEntity p = productWithColour("红色");
        BulkProductDtoIn r = emptyRow();
        r.setVariantAxes(List.of(axisWithTranslations("红色",
                new LinkedHashMap<>(Map.of("es", "Rojo", "en", "Red")))));

        BulkProductFields.applyVariantValueTranslations(p, r);

        VariantValueEntity value = p.getVariantOptions().get(0).getValues().get(0);
        assertThat(value.getTranslations()).hasSize(2);
        assertThat(value.getTranslations()).anyMatch(t -> "es".equals(t.getLanguage()) && "Rojo".equals(t.getValue()));
    }

    @Test
    void reimportarReemplazaLasTraduccionesDelValorEnVezDeAcumularlas() {
        // Reimportar es la forma de corregir una traducción mala: acumular dejaría la vieja conviviendo
        // con la nueva y el escaparate mostraría una u otra según el orden.
        ProductEntity p = productWithColour("红色");
        VariantValueEntity value = p.getVariantOptions().get(0).getValues().get(0);
        value.getTranslations().add(VariantValueTranslationEntity.builder()
                .variantValue(value).language("es").value("Colorado").build());

        BulkProductDtoIn r = emptyRow();
        r.setVariantAxes(List.of(axisWithTranslations("红色", new LinkedHashMap<>(Map.of("es", "Rojo")))));

        BulkProductFields.applyVariantValueTranslations(p, r);

        assertThat(value.getTranslations()).hasSize(1);
        assertThat(value.getTranslations().get(0).getValue()).isEqualTo("Rojo");
    }

    @Test
    void unValorSinTraduccionesEnLaFilaConservaLasQueYaTenia() {
        ProductEntity p = productWithColour("红色");
        VariantValueEntity value = p.getVariantOptions().get(0).getValues().get(0);
        value.getTranslations().add(VariantValueTranslationEntity.builder()
                .variantValue(value).language("es").value("Rojo").build());

        BulkProductDtoIn r = emptyRow();
        r.setVariantAxes(List.of(axisWithTranslations("蓝色", new LinkedHashMap<>(Map.of("es", "Azul")))));

        BulkProductFields.applyVariantValueTranslations(p, r);

        assertThat(value.getTranslations()).hasSize(1);
        assertThat(value.getTranslations().get(0).getValue()).isEqualTo("Rojo");
    }

    @Test
    void unaTraduccionConIdiomaOValorEnBlancoSeDescarta() {
        ProductEntity p = productWithColour("红色");
        BulkProductDtoIn r = emptyRow();
        Map<String, String> trs = new LinkedHashMap<>();
        trs.put("es", "Rojo");
        trs.put("  ", "Vacío");
        trs.put("fr", "   ");
        r.setVariantAxes(List.of(axisWithTranslations("红色", trs)));

        BulkProductFields.applyVariantValueTranslations(p, r);

        assertThat(p.getVariantOptions().get(0).getValues().get(0).getTranslations()).hasSize(1);
    }

    @Test
    void unaFilaSinEjesNoTocaLasTraduccionesExistentes() {
        ProductEntity p = productWithColour("红色");
        VariantValueEntity value = p.getVariantOptions().get(0).getValues().get(0);
        value.getTranslations().add(VariantValueTranslationEntity.builder()
                .variantValue(value).language("es").value("Rojo").build());

        BulkProductFields.applyVariantValueTranslations(p, emptyRow());

        assertThat(value.getTranslations()).hasSize(1);
    }

    private static ProductEntity productWithColour(String valueZh) {
        VariantValueEntity value = VariantValueEntity.builder().valueZh(valueZh).value(valueZh).build();
        value.setTranslations(new java.util.ArrayList<>());
        VariantOptionEntity option = VariantOptionEntity.builder().name("Color").nameZh("颜色").build();
        option.setValues(new java.util.ArrayList<>(List.of(value)));
        ProductEntity p = new ProductEntity();
        p.setVariantOptions(new java.util.ArrayList<>(List.of(option)));
        return p;
    }

    private static BulkProductDtoIn.BulkAxis axisWithTranslations(String valueZh, Map<String, String> translations) {
        BulkProductDtoIn.BulkAxis ax = new BulkProductDtoIn.BulkAxis();
        ax.setName("Color");
        ax.setValueTranslations(new LinkedHashMap<>(Map.of(valueZh, translations)));
        return ax;
    }
}
