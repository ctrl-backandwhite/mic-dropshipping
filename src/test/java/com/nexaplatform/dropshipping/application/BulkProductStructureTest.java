package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestPriceTier;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestVariant;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestVariantOption;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkAxis;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkTier;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkVariant;
import com.nexaplatform.dropshipping.application.service.BulkProductStructure;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Estructura de venta que se deriva de una fila de carga: ejes, variantes y tramos de precio.
 *
 * <p>El hilo conductor es que <b>nada se inventa</b>. Un catálogo de dropshipping con datos inventados
 * vende lo que no puede servir: si el proveedor no declara stock, la variante nace a cero; si no declara
 * tramos, la ficha muestra sólo el precio unitario.
 */
class BulkProductStructureTest {

    private static final BigDecimal FALLBACK = new BigDecimal("70.00");

    private static BulkProductDtoIn row() {
        return new BulkProductDtoIn();
    }

    private static BulkAxis axis(String name, List<String> values, Map<String, String> images) {
        BulkAxis ax = new BulkAxis();
        ax.setName(name);
        ax.setValues(values);
        ax.setValueImages(images == null ? null : new LinkedHashMap<>(images));
        return ax;
    }

    private static BulkVariant variant(String sku, String price, Integer stock, Map<String, String> optionValues) {
        BulkVariant v = new BulkVariant();
        v.setSku(sku);
        v.setPrice(price == null ? null : new BigDecimal(price));
        v.setStock(stock);
        v.setOptionValues(optionValues == null ? null : new LinkedHashMap<>(optionValues));
        return v;
    }

    private static BulkTier tier(Integer minQty, Integer maxQty, String unitPrice, String currency) {
        BulkTier t = new BulkTier();
        t.setMinQty(minQty);
        t.setMaxQty(maxQty);
        t.setUnitPrice(unitPrice == null ? null : new BigDecimal(unitPrice));
        t.setCurrency(currency);
        return t;
    }

    // ---------------------------------------------------------------- ejes de variación

    @Test
    void losEjesDeclaradosConservanSuOrdenYLaFotoDeCadaValor() {
        BulkProductDtoIn r = row();
        r.setVariantAxes(List.of(
                axis("Color", List.of("Rojo", "Azul"), Map.of("Rojo", "https://cdn/rojo.jpg")),
                axis("Talla", List.of("M", "L"), null)));

        List<IngestVariantOption> options = BulkProductStructure.variantOptionsOf(r);

        assertThat(options).hasSize(2);
        assertThat(options.get(0).nameZh()).isEqualTo("Color");
        assertThat(options.get(0).position()).isZero();
        assertThat(options.get(0).values().get(0).imageSourceUrl()).isEqualTo("https://cdn/rojo.jpg");
        assertThat(options.get(0).values().get(1).imageSourceUrl()).isNull();
        assertThat(options.get(1).position()).isEqualTo(1);
    }

    @Test
    void unEjeSinNombreSeDescartaSinRomperLaNumeracionDelResto() {
        BulkProductDtoIn r = row();
        r.setVariantAxes(List.of(axis("   ", List.of("x"), null), axis("Talla", List.of("M"), null)));

        List<IngestVariantOption> options = BulkProductStructure.variantOptionsOf(r);

        assertThat(options).hasSize(1);
        assertThat(options.get(0).nameZh()).isEqualTo("Talla");
        assertThat(options.get(0).position()).isZero();
    }

    @Test
    void sinEjesDeclaradosSeDerivanDeLosValoresDeLasVariantes() {
        // Sin esto, un alta que sólo trae variantes se queda sin selector en la ficha y el comprador no
        // puede elegir color ni talla.
        BulkProductDtoIn r = row();
        r.setVariants(List.of(
                variant("SKU-R-M", "70.00", 5, Map.of("Color", "Rojo")),
                variant("SKU-A-M", "70.00", 5, Map.of("Color", "Azul")),
                variant("SKU-R-L", "70.00", 5, Map.of("Color", "Rojo"))));

        List<IngestVariantOption> options = BulkProductStructure.variantOptionsOf(r);

        assertThat(options).hasSize(1);
        assertThat(options.get(0).nameZh()).isEqualTo("Color");
        // Los valores no se repiten y salen en el orden en que aparecen.
        assertThat(options.get(0).values()).extracting("valueZh").containsExactly("Rojo", "Azul");
    }

    @Test
    void losEjesDeclaradosMandanSobreLosQueSePodrianDerivar() {
        BulkProductDtoIn r = row();
        r.setVariantAxes(List.of(axis("Talla", List.of("M", "L"), null)));
        r.setVariants(List.of(variant("SKU-1", "70.00", 1, Map.of("Color", "Rojo"))));

        assertThat(BulkProductStructure.variantOptionsOf(r)).singleElement()
                .extracting("nameZh").isEqualTo("Talla");
    }

    @Test
    void unValorDeVarianteEnBlancoNoGeneraUnEjeVacio() {
        BulkProductDtoIn r = row();
        LinkedHashMap<String, String> valores = new LinkedHashMap<>();
        valores.put("Color", "   ");
        valores.put("  ", "Rojo");
        r.setVariants(List.of(variant("SKU-1", "70.00", 1, valores)));

        assertThat(BulkProductStructure.variantOptionsOf(r)).isEmpty();
    }

    @Test
    void unaFilaSinEjesNiVariantesNoTieneSelector() {
        assertThat(BulkProductStructure.variantOptionsOf(row())).isEmpty();
    }

    // ---------------------------------------------------------------- variantes

    @Test
    void cadaVarianteConservaSuPropioPrecio() {
        // Un producto de 1688 con varios colores no cuesta lo mismo en todos; cobrar un precio único
        // haría perder dinero en los caros.
        BulkProductDtoIn r = row();
        r.setVariants(List.of(variant("SKU-R", "62.50", 10, null), variant("SKU-A", "80.00", 3, null)));

        List<IngestVariant> variants = BulkProductStructure.variantsOf(r, "1688-1", "Reloj", FALLBACK);

        assertThat(variants).hasSize(2);
        assertThat(variants.get(0).price()).isEqualByComparingTo("62.50");
        assertThat(variants.get(1).price()).isEqualByComparingTo("80.00");
    }

    @Test
    void unaVarianteSinPrecioPropioHeredaElDelProducto() {
        BulkProductDtoIn r = row();
        r.setVariants(List.of(variant("SKU-R", null, 10, null)));

        assertThat(BulkProductStructure.variantsOf(r, "1688-1", "Reloj", FALLBACK).get(0).price())
                .isEqualByComparingTo("70.00");
    }

    @Test
    void unaVarianteSinSkuRecibeUnoDerivadoDelIdentificadorExterno() {
        // Hace falta para poder emparejarla en una reimportación posterior (pesos, imágenes...).
        BulkProductDtoIn r = row();
        r.setVariants(List.of(variant(null, "70.00", 1, null), variant("  ", "70.00", 1, null)));

        List<IngestVariant> variants = BulkProductStructure.variantsOf(r, "1688-1", "Reloj", FALLBACK);

        assertThat(variants.get(0).sku()).isEqualTo("1688-1-1");
        assertThat(variants.get(1).sku()).isEqualTo("1688-1-2");
    }

    @Test
    void sinVariantesDeclaradasSeCreaUnaPorDefectoParaQueElProductoSePuedaComprar() {
        List<IngestVariant> variants = BulkProductStructure.variantsOf(row(), "1688-1", "Reloj", FALLBACK);

        assertThat(variants).hasSize(1);
        assertThat(variants.get(0).sku()).isEqualTo("1688-1-DEF");
        assertThat(variants.get(0).price()).isEqualByComparingTo("70.00");
    }

    @Test
    void elStockNoSeInventaCuandoElProveedorNoLoDeclara() {
        // DROP-680: un stock inventado vende lo que no se puede servir.
        BulkProductDtoIn r = row();
        r.setVariants(List.of(variant("SKU-R", "70.00", null, null)));

        assertThat(BulkProductStructure.variantsOf(r, "1688-1", "Reloj", FALLBACK).get(0).stock()).isZero();
        assertThat(BulkProductStructure.variantsOf(row(), "1688-1", "Reloj", FALLBACK).get(0).stock()).isZero();
    }

    // ---------------------------------------------------------------- tramos de precio

    @Test
    void losTramosSePersistenTalComoLosDeclaraElProveedor() {
        BulkProductDtoIn r = row();
        r.setTieredPricing(List.of(tier(1, 9, "80.00", "CNY"), tier(10, null, "62.50", "CNY")));

        List<IngestPriceTier> tiers = BulkProductStructure.priceTiersOf(r, FALLBACK);

        assertThat(tiers).hasSize(2);
        assertThat(tiers.get(0).minQty()).isEqualTo(1);
        assertThat(tiers.get(0).maxQty()).isEqualTo(9);
        assertThat(tiers.get(1).maxQty()).isNull();          // el último tramo es abierto
        assertThat(tiers.get(1).unitPrice()).isEqualByComparingTo("62.50");
    }

    @Test
    void sinTramosNoSeInventaNingunDescuentoPorVolumen() {
        assertThat(BulkProductStructure.priceTiersOf(row(), FALLBACK)).isEmpty();

        BulkProductDtoIn vacios = row();
        vacios.setTieredPricing(List.of());
        assertThat(BulkProductStructure.priceTiersOf(vacios, FALLBACK)).isEmpty();
    }

    @Test
    void unTramoIncompletoSeCompletaConLosValoresPorDefecto() {
        BulkProductDtoIn r = row();
        r.setTieredPricing(List.of(tier(null, null, null, null)));

        IngestPriceTier t = BulkProductStructure.priceTiersOf(r, FALLBACK).get(0);

        assertThat(t.minQty()).isEqualTo(1);
        assertThat(t.unitPrice()).isEqualByComparingTo("70.00");
        assertThat(t.currency()).isEqualTo("CNY");    // los productos se persisten en yuanes
    }
}
