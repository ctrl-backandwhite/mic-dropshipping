package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.ProductSeoMetadata;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Título y descripción para buscadores.
 *
 * <p>Es lo que se ve en el resultado de Google, así que un fallo aquí no rompe nada pero cuesta visitas.
 * Lo que se fija: no se pisa lo que el operador escribió a mano, y sí se regenera lo que está
 * contaminado con ideogramas —los volcados de 1688 los cuelan en fichas ya traducidas y acaban tal cual
 * en el buscador (DROP-686)—.
 */
class ProductSeoMetadataTest {

    private static ProductTranslationEntity translation(String lang, String title, String shortDesc) {
        return ProductTranslationEntity.builder().language(lang).title(title).shortDescription(shortDesc).build();
    }

    private static ProductEntity product(String brand, ProductTranslationEntity... translations) {
        ProductEntity p = new ProductEntity();
        p.setBrand(brand);
        p.setTranslations(new ArrayList<>(List.of(translations)));
        return p;
    }

    // ---------------------------------------------------------------- título

    @Test
    void elTituloSeoSaleDelTituloTraducidoYLlevaLaMarcaAlFinal() {
        ProductEntity p = product("Casio", translation("es", "Reloj de pulsera", "Un reloj"));

        ProductSeoMetadata.generate(p);

        assertThat(p.getTranslations().get(0).getMetaTitle()).isEqualTo("Reloj de pulsera | Casio");
    }

    @Test
    void laMarcaNoSeRepiteSiElTituloYaLaLleva() {
        ProductEntity p = product("Casio", translation("es", "Reloj Casio de pulsera", "Un reloj"));

        ProductSeoMetadata.generate(p);

        assertThat(p.getTranslations().get(0).getMetaTitle()).isEqualTo("Reloj Casio de pulsera");
    }

    @Test
    void laMarcaNoSeAnadeSiElTituloSeIriaDelLimiteQueGoogleRecorta() {
        // Pasarse hace que Google corte justo por ahí y el resultado se lea peor que sin marca.
        String largo = "Reloj de pulsera para hombre con correa de acero inoxidable y esfera";
        ProductEntity p = product("Casio", translation("es", largo, "Un reloj"));

        ProductSeoMetadata.generate(p);

        assertThat(p.getTranslations().get(0).getMetaTitle()).isEqualTo(largo);
    }

    @Test
    void unTituloDesmesuradoSeCapaALoQueAdmiteLaColumna() {
        ProductEntity p = product(null, translation("es", "x".repeat(400), "desc"));

        ProductSeoMetadata.generate(p);

        assertThat(p.getTranslations().get(0).getMetaTitle()).hasSize(ProductSeoMetadata.TITLE_MAX);
    }

    // ---------------------------------------------------------------- descripción

    @Test
    void laDescripcionSeoPrefiereLaCortaSobreLaLarga() {
        ProductTranslationEntity tr = translation("es", "Reloj", "Resumen corto");
        tr.setDescription("Descripción larga con todo el detalle");

        ProductSeoMetadata.generate(product(null, tr));

        assertThat(tr.getMetaDescription()).isEqualTo("Resumen corto");
    }

    @Test
    void sinDescripcionCortaSeUsaLaLargaYSiNoElTitulo() {
        ProductTranslationEntity conLarga = translation("es", "Reloj", null);
        conLarga.setDescription("Descripción larga");
        ProductSeoMetadata.generate(product(null, conLarga));
        assertThat(conLarga.getMetaDescription()).isEqualTo("Descripción larga");

        ProductTranslationEntity sinNada = translation("es", "Reloj", null);
        ProductSeoMetadata.generate(product(null, sinNada));
        assertThat(sinNada.getMetaDescription()).isEqualTo("Reloj");
    }

    @Test
    void unaDescripcionLargaSeRecortaDondeGoogleRecortaYSeMarcaConPuntosSuspensivos() {
        ProductTranslationEntity tr = translation("es", "Reloj", "palabra ".repeat(50));

        ProductSeoMetadata.generate(product(null, tr));

        assertThat(tr.getMetaDescription()).hasSizeLessThanOrEqualTo(ProductSeoMetadata.DESCRIPTION_MAX);
        assertThat(tr.getMetaDescription()).endsWith("…");
    }

    // ---------------------------------------------------------------- lo escrito a mano

    @Test
    void loQueEscribioElOperadorNoSePisa() {
        ProductTranslationEntity tr = translation("es", "Reloj de pulsera", "Un reloj");
        tr.setMetaTitle("Mi título elegido a mano");
        tr.setMetaDescription("Mi descripción elegida a mano");

        ProductSeoMetadata.generate(product("Casio", tr));

        assertThat(tr.getMetaTitle()).isEqualTo("Mi título elegido a mano");
        assertThat(tr.getMetaDescription()).isEqualTo("Mi descripción elegida a mano");
    }

    @Test
    void unMetadatoEnBlancoSiSeRegenera() {
        ProductTranslationEntity tr = translation("es", "Reloj de pulsera", "Un reloj");
        tr.setMetaTitle("   ");

        ProductSeoMetadata.generate(product(null, tr));

        assertThat(tr.getMetaTitle()).isEqualTo("Reloj de pulsera");
    }

    // ---------------------------------------------------------------- ideogramas colados

    @Test
    void unMetadatoConIdeogramasEnUnaFichaEnEspanolSeRegeneraLimpio() {
        // DROP-686: los volcados de 1688 cuelan ideogramas en fichas ya traducidas, y eso llega tal cual
        // al resultado de búsqueda.
        ProductTranslationEntity tr = translation("es", "Reloj de pulsera", "Un reloj");
        tr.setMetaTitle("Reloj 露趾 de pulsera");

        ProductSeoMetadata.generate(product(null, tr));

        assertThat(tr.getMetaTitle()).isEqualTo("Reloj de pulsera");
    }

    @Test
    void enLaFichaEnChinoLosIdeogramasSonLoCorrectoYNoSeTocan() {
        ProductTranslationEntity tr = translation("zh", "男士石英手表", "石英表");
        tr.setMetaTitle("男士石英手表");

        ProductSeoMetadata.generate(product(null, tr));

        assertThat(tr.getMetaTitle()).isEqualTo("男士石英手表");
    }

    @Test
    void alQuitarIdeogramasNoQuedanSeparadoresColgando() {
        // Sin la limpieza, "Reloj | 手表" quedaría como "Reloj |" y se leería roto en el buscador.
        assertThat(ProductSeoMetadata.sanitize("Reloj | 手表", false)).isEqualTo("Reloj");
        assertThat(ProductSeoMetadata.sanitize("手表 · Reloj", false)).isEqualTo("Reloj");
        assertThat(ProductSeoMetadata.sanitize("Reloj  ·  ·  hombre", false)).isEqualTo("Reloj · hombre");
    }

    @Test
    void enChinoElTextoNoSeLimpiaSoloSeColapsanLosEspacios() {
        assertThat(ProductSeoMetadata.sanitize("男士  石英手表", true)).isEqualTo("男士 石英手表");
    }

    @ParameterizedTest
    @ValueSource(strings = {"男士石英手表", "2024新款 T恤", "Reloj 露趾"})
    void reconoceLosIdeogramas(String text) {
        assertThat(ProductSeoMetadata.hasCjk(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Reloj de pulsera", "123456", ""})
    void noConfundeTextoLatinoNiNumerosConIdeogramas(String text) {
        assertThat(ProductSeoMetadata.hasCjk(text)).isFalse();
    }

    @Test
    void unTextoAusenteNoRompeLaLimpieza() {
        assertThat(ProductSeoMetadata.hasCjk(null)).isFalse();
        assertThat(ProductSeoMetadata.sanitize(null, false)).isEmpty();
    }

    @Test
    void unaTraduccionSinTituloSeSaltaEnVezDeGenerarSeoVacio() {
        ProductTranslationEntity tr = translation("es", "   ", "Un reloj");

        ProductSeoMetadata.generate(product("Casio", tr));

        assertThat(tr.getMetaTitle()).isNull();
        assertThat(tr.getMetaDescription()).isNull();
    }
}
