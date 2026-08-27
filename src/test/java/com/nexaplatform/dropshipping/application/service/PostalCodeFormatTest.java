package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.enums.PostalCodeFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El código postal tiene que ser el del país que se ha elegido.
 *
 * <p>Nace de un agujero concreto: la lista de destinos que el transportista no sirve se compara por
 * número —Baleares es 07000-07999—, así que escribir «07001A» dejaba el código fuera de toda
 * comparación y el pedido pasaba. Se cobraba, y al despachar no había forma de emitir la guía. La
 * solución no es aflojar la comparación, sino no aceptar un código postal que ese país no usa.
 *
 * <p><b>Ante un país que no conocemos, no se valida.</b> Hay países sin código postal y otros cuyo
 * formato no tenemos contrastado; inventarles una regla impediría comprar a gente cuya dirección es
 * perfectamente correcta, que es peor que el problema que se arregla.
 */
class PostalCodeFormatTest {

    @Test
    @DisplayName("España: cinco dígitos, y nada más")
    void espanaSonCincoDigitos() {
        assertThat(PostalCodeFormat.isValid("ES", "28001")).isTrue();
        assertThat(PostalCodeFormat.isValid("ES", "07001")).as("Palma también es válido de formato").isTrue();
        assertThat(PostalCodeFormat.isValid("ES", "07001A")).as("el que esquivaba el bloqueo").isFalse();
        assertThat(PostalCodeFormat.isValid("ES", "2800")).isFalse();
        assertThat(PostalCodeFormat.isValid("ES", "ES-28001")).isFalse();
    }

    @Test
    @DisplayName("los formatos con letras o guion se aceptan tal como los escribe cada país")
    void cadaPaisConSuFormato() {
        assertThat(PostalCodeFormat.isValid("PT", "1000-001")).as("Portugal, NNNN-NNN").isTrue();
        assertThat(PostalCodeFormat.isValid("NL", "1234 AB")).as("Países Bajos, NNNN LL").isTrue();
        assertThat(PostalCodeFormat.isValid("GB", "SW1A 1AA")).as("Reino Unido").isTrue();
        assertThat(PostalCodeFormat.isValid("CA", "K1A 0B1")).as("Canadá").isTrue();
        assertThat(PostalCodeFormat.isValid("US", "10001-1234")).as("Estados Unidos, ZIP+4").isTrue();
        assertThat(PostalCodeFormat.isValid("JP", "100-0001")).as("Japón").isTrue();
    }

    @Test
    @DisplayName("se toleran los espacios de más y las minúsculas, que son forma de escribir, no error")
    void toleraEspaciosYMinusculas() {
        assertThat(PostalCodeFormat.isValid("ES", " 28001 ")).isTrue();
        assertThat(PostalCodeFormat.isValid("NL", "1234ab")).isTrue();
        assertThat(PostalCodeFormat.isValid("GB", "sw1a1aa")).isTrue();
        assertThat(PostalCodeFormat.isValid("PT", "1000 001")).isTrue();
    }

    @Test
    @DisplayName("un código de otro país no cuela")
    void unCodigoDeOtroPaisNoCuela() {
        assertThat(PostalCodeFormat.isValid("ES", "1000-001")).as("portugués en España").isFalse();
        assertThat(PostalCodeFormat.isValid("DE", "SW1A 1AA")).as("británico en Alemania").isFalse();
        assertThat(PostalCodeFormat.isValid("FR", "1234 AB")).as("neerlandés en Francia").isFalse();
    }

    @Test
    @DisplayName("en un país con formato conocido, el código postal es obligatorio")
    void enPaisConocidoElCodigoEsObligatorio() {
        // Sin él no hay forma de saber si el destino es servible, y el transportista tampoco lo admite.
        assertThat(PostalCodeFormat.isValid("ES", null)).isFalse();
        assertThat(PostalCodeFormat.isValid("ES", "")).isFalse();
        assertThat(PostalCodeFormat.isValid("ES", "   ")).isFalse();
    }

    @Test
    @DisplayName("un país que no conocemos no se valida: ante la duda, no se bloquea la compra")
    void unPaisDesconocidoNoSeValida() {
        // Hong Kong y Emiratos no usan código postal; de otros no tenemos el formato contrastado.
        assertThat(PostalCodeFormat.isValid("HK", null)).isTrue();
        assertThat(PostalCodeFormat.isValid("AE", "")).isTrue();
        assertThat(PostalCodeFormat.isValid("ZZ", "lo que sea")).isTrue();
        assertThat(PostalCodeFormat.isValid(null, "28001")).isTrue();
    }

    @Test
    @DisplayName("el país se compara sin distinguir mayúsculas ni espacios")
    void elPaisSeNormaliza() {
        assertThat(PostalCodeFormat.isValid("es", "28001")).isTrue();
        assertThat(PostalCodeFormat.isValid(" ES ", "28001")).isTrue();
        assertThat(PostalCodeFormat.isValid("es", "28001A")).isFalse();
    }

    @Test
    @DisplayName("cada país conocido publica un ejemplo, para poder decir en pantalla qué se espera")
    void cadaPaisPublicaUnEjemplo() {
        assertThat(PostalCodeFormat.exampleFor("ES")).isEqualTo("28001");
        assertThat(PostalCodeFormat.exampleFor("PT")).isEqualTo("1000-001");
        assertThat(PostalCodeFormat.exampleFor("HK")).as("país sin formato: nada que enseñar").isEmpty();
    }

    @Test
    @DisplayName("el ejemplo de cada país cumple su propio formato")
    void losEjemplosSonCoherentesConSuPatron() {
        // Si un ejemplo no valida, el formulario estaría pidiendo algo que él mismo rechaza.
        for (PostalCodeFormat formato : PostalCodeFormat.values()) {
            assertThat(PostalCodeFormat.isValid(formato.name(), formato.example()))
                    .as("el ejemplo de %s", formato.name()).isTrue();
        }
    }
}
