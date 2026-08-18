package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.application.service.CustomsDataCheck.CustomsField;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regla única de los cinco datos aduaneros obligatorios.
 *
 * <p>Estos cinco datos los exige el transportista en CADA línea declarada y hasta ahora solo se
 * comprobaban al transmitir la guía, cuando el pedido ya estaba cobrado. Al vivir la regla en un solo
 * sitio, el catálogo y el despacho no pueden discrepar sobre qué es «completo»: el producto que el admin
 * da por listo para vender es exactamente el que el transportista va a aceptar.
 */
class CustomsDataCheckTest {

    private static ProductTranslationEntity traduccion(String idioma, String titulo) {
        ProductTranslationEntity t = new ProductTranslationEntity();
        t.setLanguage(idioma);
        t.setTitle(titulo);
        return t;
    }

    /** Producto con TODO lo obligatorio: es el punto de partida al que cada test le quita una cosa. */
    private static ProductEntity productoCompleto() {
        ProductEntity p = new ProductEntity();
        p.setTitleZh("男士石英手表");
        p.setHsCode("9102190000");
        p.setWeightGrams(300);
        p.setBasePrice(new BigDecimal("12.50"));
        p.setTranslations(List.of(traduccion("en", "Men's quartz watch"),
                traduccion("zh", "男士石英手表长方形表壳")));
        return p;
    }

    @Test
    @DisplayName("un producto con los cinco datos no tiene nada que reprochar")
    void unProductoCompletoNoTieneFaltantes() {
        assertThat(CustomsDataCheck.faltantesDe(productoCompleto())).isEmpty();
    }

    @Test
    @DisplayName("sin partida arancelaria se señala HSCode")
    void sinPartidaArancelariaSeSenalaHsCode() {
        ProductEntity p = productoCompleto();
        p.setHsCode("  ");

        assertThat(CustomsDataCheck.faltantesDe(p)).containsExactly(CustomsField.HS_CODE);
    }

    @Test
    @DisplayName("sin traducción al inglés se señala EName")
    void sinTraduccionInglesaSeSenalaEName() {
        ProductEntity p = productoCompleto();
        p.setTranslations(List.of(traduccion("zh", "男士石英手表长方形表壳")));

        assertThat(CustomsDataCheck.faltantesDe(p)).containsExactly(CustomsField.ENGLISH_NAME);
    }

    @Test
    @DisplayName("un nombre chino escrito en alfabeto latino cuenta como ausente")
    void elNombreChinoEnAlfabetoLatinoCuentaComoAusente() {
        // Es el fallo real del catálogo: title_zh quedó poblado con el título en español, así que el dato
        // «está» pero YunExpress lo rechaza. Mirar solo si el campo viene relleno no detecta nada.
        ProductEntity p = productoCompleto();
        p.setTitleZh("Reloj de pulsera para hombre");
        p.setTranslations(List.of(traduccion("en", "Men's quartz watch"),
                traduccion("zh", "Reloj de pulsera para hombre")));

        assertThat(CustomsDataCheck.faltantesDe(p)).containsExactly(CustomsField.CHINESE_NAME);
    }

    @Test
    @DisplayName("sin peso en el producto ni en todas sus variantes se señala UnitWeight")
    void sinPesoEnProductoNiEnTodasLasVariantesSeSenalaElPeso() {
        // El peso se declara por la variante COMPRADA, así que basta con que una sola variante no lo
        // tenga para que ese pedido concreto salga con la declaración coja.
        ProductEntity p = productoCompleto();
        p.setWeightGrams(null);
        p.setVariants(List.of(variante(250), variante(null)));

        assertThat(CustomsDataCheck.faltantesDe(p)).containsExactly(CustomsField.UNIT_WEIGHT);
    }

    @Test
    @DisplayName("el peso de las variantes sirve cuando el producto no lo lleva")
    void elPesoDeLasVariantesSirveCuandoElProductoNoLoLleva() {
        ProductEntity p = productoCompleto();
        p.setWeightGrams(null);
        p.setVariants(List.of(variante(250), variante(400)));

        assertThat(CustomsDataCheck.faltantesDe(p)).isEmpty();
    }

    @Test
    @DisplayName("sin precio base ni precio de variante se señala el valor declarado")
    void sinPrecioSeSenalaElValorDeclarado() {
        ProductEntity p = productoCompleto();
        p.setBasePrice(BigDecimal.ZERO);

        assertThat(CustomsDataCheck.faltantesDe(p)).containsExactly(CustomsField.DECLARED_VALUE);
    }

    @Test
    @DisplayName("se acumulan todos los datos que faltan, no solo el primero")
    void seAcumulanTodosLosDatosQueFaltan() {
        // El admin tiene que poder arreglarlo de una pasada: enseñarle los fallos de uno en uno obliga a
        // guardar, reintentar y descubrir el siguiente.
        ProductEntity p = new ProductEntity();

        assertThat(CustomsDataCheck.faltantesDe(p)).containsExactlyInAnyOrder(CustomsField.ENGLISH_NAME,
                CustomsField.CHINESE_NAME, CustomsField.HS_CODE, CustomsField.UNIT_WEIGHT,
                CustomsField.DECLARED_VALUE);
    }

    @Test
    @DisplayName("la descripción para el admin nombra el producto y cada dato que falta")
    void laDescripcionNombraElProductoYCadaDatoQueFalta() {
        String frase = CustomsDataCheck.describe("Reloj de pulsera (SKU-1)",
                List.of(CustomsField.HS_CODE, CustomsField.CHINESE_NAME));

        assertThat(frase).contains("Reloj de pulsera (SKU-1)")
                .contains("partida arancelaria (HSCode)")
                .contains("nombre en chino (CName)");
    }

    @Test
    @DisplayName("reconoce ideogramas y rechaza texto latino o numérico")
    void reconoceIdeogramasYRechazaTextoLatinoONumerico() {
        assertThat(CustomsDataCheck.tieneIdeogramas("男士石英手表")).isTrue();
        assertThat(CustomsDataCheck.tieneIdeogramas("2024新款 T恤")).isTrue();
        assertThat(CustomsDataCheck.tieneIdeogramas("Reloj de pulsera")).isFalse();
        assertThat(CustomsDataCheck.tieneIdeogramas("123456")).isFalse();
        assertThat(CustomsDataCheck.tieneIdeogramas("")).isFalse();
        assertThat(CustomsDataCheck.tieneIdeogramas(null)).isFalse();
    }

    private static ProductVariantEntity variante(Integer gramos) {
        ProductVariantEntity v = new ProductVariantEntity();
        v.setWeightGrams(gramos);
        v.setPrice(new BigDecimal("12.50"));
        return v;
    }
}
