package com.nexaplatform.dropshipping.api.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Catálogo de mensajes de error localizados. Es la única fuente de los textos que ve el usuario, así
 * que lo que se fija aquí es que ningún código se quede sin traducir en alguno de los 8 idiomas y que
 * un idioma o un código desconocidos degraden con elegancia en vez de dejar la respuesta vacía.
 */
class Cov05ErrorCodeTest {

    private static final List<String> LANGUAGES = List.of("es", "en", "pt", "zh", "fr", "de", "it", "nl");

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    @DisplayName("todo código tiene texto en los 8 idiomas soportados")
    void todoCodigoTieneTextoEnLosOchoIdiomas(ErrorCode code) {
        for (String lang : LANGUAGES) {
            assertThat(code.of(lang)).as("idioma %s de %s", lang, code).isNotBlank();
        }
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    @DisplayName("ningún idioma se queda con el texto español copiado (traducción olvidada)")
    void ningunIdiomaRepiteElTextoEspanol(ErrorCode code) {
        String spanish = code.of("es");
        for (String lang : LANGUAGES) {
            if (!"es".equals(lang)) {
                // Copiar el español a otro idioma es el síntoma típico de un alta a medias.
                assertThat(code.of(lang)).as("idioma %s de %s", lang, code).isNotEqualTo(spanish);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ru", "xx", "esperanto", ""})
    @DisplayName("un idioma no soportado cae al español, nunca a un texto vacío")
    void unIdiomaNoSoportadoCaeAlEspanol(String lang) {
        assertThat(ErrorCode.ORDER_NOT_FOUND.of(lang)).isEqualTo(ErrorCode.ORDER_NOT_FOUND.of("es"));
    }

    @Test
    @DisplayName("sin idioma indicado se responde en español")
    void sinIdiomaSeRespondeEnEspanol() {
        assertThat(ErrorCode.CART_EMPTY.of(null)).isEqualTo(ErrorCode.CART_EMPTY.of("es"));
    }

    @ParameterizedTest
    @CsvSource({"en-GB,en", "EN,en", "pt_BR,pt", "  ZH-Hans  ,zh", "fr-CA,fr", "de_AT,de", "it-CH,it", "nl-BE,nl"})
    @DisplayName("la etiqueta de idioma se normaliza (región, guion bajo y mayúsculas)")
    void laEtiquetaDeIdiomaSeNormaliza(String requested, String expectedLanguage) {
        // El Accept-Language del navegador llega como "en-GB" o "pt_BR": debe resolver igual que "en"/"pt".
        assertThat(ErrorCode.WALLET_INSUFFICIENT_BALANCE.of(requested))
                .isEqualTo(ErrorCode.WALLET_INSUFFICIENT_BALANCE.of(expectedLanguage));
    }

    @Test
    @DisplayName("localizar un código que no está catalogado devuelve nulo (el llamante usa su mensaje)")
    void localizarUnCodigoNoCatalogadoDevuelveNulo() {
        assertThat(ErrorCode.localize("NO_EXISTE_ESTE_CODIGO", "es")).isNull();
        assertThat(ErrorCode.localize(null, "es")).isNull();
        assertThat(ErrorCode.localize("   ", "es")).isNull();
    }

    @Test
    @DisplayName("el código se recorta antes de buscarlo")
    void elCodigoSeRecortaAntesDeBuscarlo() {
        assertThat(ErrorCode.localize("  ORDER_NOT_FOUND  ", "es")).isEqualTo(ErrorCode.ORDER_NOT_FOUND.of("es"));
    }

    @Test
    @DisplayName("cada tipo de excepción tiene su mensaje genérico localizado")
    void cadaTipoDeExcepcionTieneSuMensajeGenerico() {
        // Sin estos genéricos, un error sin código específico saldría en español a un usuario chino.
        assertThat(ErrorCode.localize("BR001", "en")).isNotBlank();
        assertThat(ErrorCode.localize("ENF001", "zh")).isNotBlank();
        assertThat(ErrorCode.localize("AE001", "pt")).isNotBlank();
        assertThat(ErrorCode.localize("DM001", "fr")).isNotBlank();
        assertThat(ErrorCode.localize("CF001", "de")).isNotBlank();
        assertThat(ErrorCode.localize("RL001", "it")).isNotBlank();
        assertThat(ErrorCode.localize("IS001", "nl")).isNotBlank();
    }

    @Test
    @DisplayName("los códigos por defecto de las excepciones base están catalogados")
    void losCodigosPorDefectoDeLasExcepcionesEstanCatalogados() {
        // BusinessException usa BR001 y NotFoundException ENF001: si faltaran, el 422/404 saldría sin texto.
        assertThat(ErrorCode.localize(new BusinessException("x").getCode(), "en")).isNotBlank();
        assertThat(ErrorCode.localize(new NotFoundException("x").getCode(), "en")).isNotBlank();
    }

    @Test
    @DisplayName("el mensaje que ve el usuario nunca es el propio código del error")
    void elMensajeNuncaEsElCodigoDelError() {
        for (ErrorCode code : ErrorCode.values()) {
            for (String lang : LANGUAGES) {
                // Un texto igual al identificador significaría que la constante se dio de alta sin redactar.
                assertThat(code.of(lang)).as("%s/%s", code, lang).isNotEqualTo(code.name());
            }
        }
    }
}
