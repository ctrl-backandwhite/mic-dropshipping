package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkAttr;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkVariant;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkTier;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.application.service.BulkProductRules;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryAttributeSchemaEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reglas de calidad que una fila debe cumplir para entrar en el catálogo.
 *
 * <p>Son las mismas que el manual de carga exige a mano: sin precio real, sin envío, sin IVA o sin
 * título, el producto no se sube. Estaban repartidas por las 255 líneas del importador y no había forma
 * de probarlas sin montar media aplicación; al reunirlas en {@code BulkProductRules} se prueban solas.
 *
 * <p>El mensaje importa tanto como el rechazo: el importador procesa lotes de cientos de filas y acumula
 * los errores, así que tiene que decir QUÉ falta y de QUÉ producto. Un "dato inválido" a secas obliga a
 * revisar el lote entero para dar con la fila mala.
 */
class BulkProductRulesTest {

    private static BulkProductDtoIn row() {
        BulkProductDtoIn r = new BulkProductDtoIn();
        r.setTitleEs("Reloj de pulsera para hombre");
        r.setPrice(new BigDecimal("70.00"));
        r.setShippingCny(new BigDecimal("12.00"));
        r.setIvaCny(new BigDecimal("14.70"));
        return r;
    }

    private static CategoryAttributeSchemaEntity attr(String key, boolean required) {
        CategoryAttributeSchemaEntity a = new CategoryAttributeSchemaEntity();
        a.setAttrKey(key);
        a.setRequired(required);
        return a;
    }

    private static BulkAttr given(String key, String value) {
        BulkAttr a = new BulkAttr();
        a.setKey(key);
        a.setValue(value);
        return a;
    }

    private static BulkTier tier(int minQty, String unitPrice) {
        BulkTier t = new BulkTier();
        t.setMinQty(minQty);
        t.setUnitPrice(unitPrice == null ? null : new BigDecimal(unitPrice));
        return t;
    }

    // ---------------------------------------------------------------- título

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void sinTituloEnNingunIdiomaLaFilaSeRechaza(String title) {
        assertThatThrownBy(() -> BulkProductRules.assertTitle(title))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Falta el título");
    }

    @Test
    void unTituloNuloTambienSeRechaza() {
        assertThatThrownBy(() -> BulkProductRules.assertTitle(null))
                .isInstanceOf(BusinessException.class);
    }

    // ---------------------------------------------------------------- precio

    @Test
    void elPrecioExplicitoManda() {
        assertThat(BulkProductRules.resolvePrice(row(), "Reloj")).isEqualByComparingTo("70.00");
    }

    @Test
    void sinPrecioExplicitoSeTomaElTramoMasBaratoQueEsUnPrecioReal() {
        // El de mayor cantidad es el más bajo y sigue siendo un precio de verdad del proveedor; inventar
        // uno haría que el margen se calculara sobre una base que no existe.
        BulkProductDtoIn r = row();
        r.setPrice(null);
        r.setTieredPricing(List.of(tier(1, "80.00"), tier(50, "62.50"), tier(10, "70.00")));

        assertThat(BulkProductRules.resolvePrice(r, "Reloj")).isEqualByComparingTo("62.50");
    }

    @Test
    void losTramosSinPrecioSeIgnoranAlBuscarElMasBarato() {
        BulkProductDtoIn r = row();
        r.setPrice(null);
        r.setTieredPricing(List.of(tier(1, null), tier(50, "62.50"), tier(100, null)));

        assertThat(BulkProductRules.resolvePrice(r, "Reloj")).isEqualByComparingTo("62.50");
    }

    @Test
    void sinPrecioNiTramosLaFilaSeRechazaDiciendoDeQueProducto() {
        BulkProductDtoIn r = row();
        r.setPrice(null);

        assertThatThrownBy(() -> BulkProductRules.resolvePrice(r, "Reloj de pulsera"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Falta el precio real")
                .hasMessageContaining("Reloj de pulsera");
    }

    @Test
    void unosTramosTodosSinPrecioEquivalenANoTenerPrecio() {
        BulkProductDtoIn r = row();
        r.setPrice(null);
        r.setTieredPricing(List.of(tier(1, null), tier(50, null)));

        assertThatThrownBy(() -> BulkProductRules.resolvePrice(r, "Reloj"))
                .isInstanceOf(BusinessException.class);
    }

    // ---------------------------------------------------------------- envío e IVA

    @Test
    void sinEnvioLaFilaSeRechaza() {
        // El total es base×margen + IVA + envío: sin envío el producto se vendería por debajo de coste.
        BulkProductDtoIn r = row();
        r.setShippingCny(null);

        assertThatThrownBy(() -> BulkProductRules.assertShippingAndVat(r, "Reloj"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("shippingCny")
                .hasMessageContaining("Reloj");
    }

    @Test
    void sinIvaLaFilaSeRechaza() {
        BulkProductDtoIn r = row();
        r.setIvaCny(null);

        assertThatThrownBy(() -> BulkProductRules.assertShippingAndVat(r, "Reloj"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ivaCny");
    }

    @Test
    void conEnvioEIvaAceroLaFilaPasaPorqueCeroEsUnDatoYNoUnaAusencia() {
        BulkProductDtoIn r = row();
        r.setShippingCny(BigDecimal.ZERO);
        r.setIvaCny(BigDecimal.ZERO);

        assertThatCode(() -> BulkProductRules.assertShippingAndVat(r, "Reloj")).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- atributos de la categoría

    @Test
    void unaCategoriaSinAtributosObligatoriosNoExigeNada() {
        BulkProductDtoIn r = row();
        assertThatCode(() -> BulkProductRules.assertRequiredAttributes(r, List.of(), "moda-relojes"))
                .doesNotThrowAnyException();
        assertThatCode(() -> BulkProductRules.assertRequiredAttributes(r,
                List.of(attr("material", false)), "moda-relojes")).doesNotThrowAnyException();
    }

    @Test
    void faltarUnAtributoObligatorioRechazaLaFilaDiciendoCualYDeQueCategoria() {
        BulkProductDtoIn r = row();
        List<CategoryAttributeSchemaEntity> schema = List.of(attr("material", true));

        assertThatThrownBy(() -> BulkProductRules.assertRequiredAttributes(r, schema, "moda-relojes"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("material")
                .hasMessageContaining("moda-relojes");
    }

    @Test
    void elAtributoObligatorioSeReconoceSinImportarMayusculasNiEspacios() {
        BulkProductDtoIn r = row();
        r.setAttributes(List.of(given("  Material  ", "Acero inoxidable")));

        assertThatCode(() -> BulkProductRules.assertRequiredAttributes(r,
                List.of(attr("material", true)), "moda-relojes")).doesNotThrowAnyException();
    }

    @Test
    void unAtributoPresentePeroVacioNoCuentaComoAportado() {
        // Rellenar la clave con "" pasaría el filtro y dejaría la ficha igual de incompleta.
        BulkProductDtoIn r = row();
        r.setAttributes(List.of(given("material", "   ")));
        List<CategoryAttributeSchemaEntity> schema = List.of(attr("material", true));

        assertThatThrownBy(() -> BulkProductRules.assertRequiredAttributes(r, schema, "moda-relojes"))
                .isInstanceOf(BusinessException.class);
    }

    // ---------------------------------------------------------------- identificador externo

    @Test
    void elIdentificadorDeLaFilaSeRespetaTalCualParaQueLaReimportacionActualiceEnSitio() {
        // Es la clave del UPSERT: cambiarlo duplicaría el producto y perdería favoritos y pedidos.
        BulkProductDtoIn r = row();
        r.setExternalId("  1688-123456789  ");

        assertThat(BulkProductRules.externalIdOf(r, "Reloj", s -> s, 42L)).isEqualTo("1688-123456789");
    }

    @Test
    void sinIdentificadorSeDerivaDelTituloConUnSufijoQueLoHaceUnico() {
        BulkProductDtoIn r = row();

        String id = BulkProductRules.externalIdOf(r, "Reloj de pulsera", s -> s.replace(' ', '-'), 42L);

        assertThat(id).isEqualTo("BULK-Reloj-de-pulsera-42");
    }

    @Test
    void unTituloLargoNoDesbordaLaColumna() {
        // El varchar(120) tumbaba la importación entera con títulos largos de 1688.
        BulkProductDtoIn r = row();

        String id = BulkProductRules.externalIdOf(r, "x".repeat(400), s -> s, 1234567890123L);

        assertThat(id).hasSizeLessThanOrEqualTo(BulkProductRules.MAX_EXTERNAL_ID).startsWith("BULK-");
    }

    @Test
    void unIdentificadorPropioDemasiadoLargoTambienSeCapa() {
        BulkProductDtoIn r = row();
        r.setExternalId("1688-" + "9".repeat(200));

        assertThat(BulkProductRules.externalIdOf(r, "Reloj", s -> s, 1L))
                .hasSize(BulkProductRules.MAX_EXTERNAL_ID);
    }

    @Test
    void unIdentificadorEnBlancoSeTrataComoAusente() {
        BulkProductDtoIn r = row();
        r.setExternalId("   ");

        assertThat(BulkProductRules.externalIdOf(r, "Reloj", s -> s, 7L)).isEqualTo("BULK-Reloj-7");
    }

    // ---------------------------------------------------------------- imágenes

    @Test
    void lasImagenesSeTomanEnElOrdenDeLaFilaYSinRepetir() {
        // El orden es el que el producto tiene en el proveedor y la ficha lo respeta; deduplicar
        // reordenando cambiaría cuál es la foto principal.
        BulkProductDtoIn r = row();
        r.setImageUrls(List.of("https://cdn/a.jpg", "https://cdn/b.jpg", "https://cdn/a.jpg"));

        assertThat(BulkProductRules.imageUrlsOf(r, "Reloj"))
                .containsExactly("https://cdn/a.jpg", "https://cdn/b.jpg");
    }

    @Test
    void elAtajoDeUnaSolaImagenSeSumaALaLista() {
        BulkProductDtoIn r = row();
        r.setImageUrls(List.of("https://cdn/a.jpg"));
        r.setImageUrl("https://cdn/z.jpg");

        assertThat(BulkProductRules.imageUrlsOf(r, "Reloj"))
                .containsExactly("https://cdn/a.jpg", "https://cdn/z.jpg");
    }

    @Test
    void lasUrlEnBlancoSeDescartanSinContarComoImagen() {
        BulkProductDtoIn r = row();
        r.setImageUrls(java.util.Arrays.asList("  https://cdn/a.jpg  ", "", "   ", null));

        assertThat(BulkProductRules.imageUrlsOf(r, "Reloj")).containsExactly("https://cdn/a.jpg");
    }

    @Test
    void sinImagenDeProductoSeRecurreALaDeLaVariante() {
        // Hay productos cuya única foto vive en el color; rechazarlos por eso perdería la carga.
        BulkProductDtoIn r = row();
        BulkVariant v = new BulkVariant();
        v.setSku("SKU-ROJO");
        v.setImageUrl("https://cdn/rojo.jpg");
        r.setVariants(List.of(v));

        assertThat(BulkProductRules.imageUrlsOf(r, "Reloj")).containsExactly("https://cdn/rojo.jpg");
    }

    @Test
    void sinImagenDeProductoNiDeVarianteSeRecurreALaDelValorDelEje() {
        BulkProductDtoIn r = row();
        BulkProductDtoIn.BulkAxis ax = new BulkProductDtoIn.BulkAxis();
        ax.setName("Color");
        ax.setValueImages(new java.util.LinkedHashMap<>(java.util.Map.of("Rojo", "https://cdn/rojo.jpg")));
        r.setVariantAxes(List.of(ax));

        assertThat(BulkProductRules.imageUrlsOf(r, "Reloj")).containsExactly("https://cdn/rojo.jpg");
    }

    @Test
    void laImagenDeProductoTienePrioridadSobreLaDeLaVariante() {
        BulkProductDtoIn r = row();
        r.setImageUrls(List.of("https://cdn/producto.jpg"));
        BulkVariant v = new BulkVariant();
        v.setImageUrl("https://cdn/rojo.jpg");
        r.setVariants(List.of(v));

        assertThat(BulkProductRules.imageUrlsOf(r, "Reloj")).containsExactly("https://cdn/producto.jpg");
    }

    @Test
    void unProductoSinNingunaImagenNoSeCargaYElMensajeDiceCual() {
        // Regla dura del manual de carga: un producto sin foto no se puede vender, así que vale más que
        // la fila no entre a que entre vacía.
        BulkProductDtoIn r = row();
        r.setExternalId("1688-123456789");

        assertThatThrownBy(() -> BulkProductRules.imageUrlsOf(r, "Reloj"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no tiene imágenes")
                .hasMessageContaining("1688-123456789");
    }

    @Test
    void sinIdentificadorElMensajeDeFaltaDeImagenIdentificaPorElTitulo() {
        BulkProductDtoIn r = row();
        assertThatThrownBy(() -> BulkProductRules.imageUrlsOf(r, "Reloj de pulsera"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Reloj de pulsera");
    }
}
