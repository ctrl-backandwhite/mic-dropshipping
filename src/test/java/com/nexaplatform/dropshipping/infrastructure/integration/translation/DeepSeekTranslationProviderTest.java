package com.nexaplatform.dropshipping.infrastructure.integration.translation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Traducción con DeepSeek · la misma cuenta y el mismo modelo que el asistente")
class DeepSeekTranslationProviderTest {

    private DeepSeekTranslationProvider provider;

    @BeforeEach
    void setUp() {
        provider = new DeepSeekTranslationProvider();
        ReflectionTestUtils.setField(provider, "apiKey", "sk-de-prueba");
        ReflectionTestUtils.setField(provider, "model", "deepseek-v4-flash");
        ReflectionTestUtils.setField(provider, "baseUrl", "https://api.deepseek.com");
        ReflectionTestUtils.setField(provider, "timeoutSeconds", 60);
    }

    @Test
    @DisplayName("Sin clave no está disponible")
    void sinClaveNoEstaDisponible() {
        ReflectionTestUtils.setField(provider, "apiKey", "");
        assertFalse(provider.available());
    }

    @Test
    @DisplayName("Con clave está disponible y se identifica como deepseek")
    void conClaveDisponible() {
        assertTrue(provider.available());
        assertEquals("deepseek", provider.name());
    }

    @Test
    @DisplayName("Un texto vacío no gasta una llamada")
    void textoVacioNoLlama() {
        // Si intentara llamar, sin red la prueba fallaría: que devuelva sin más lo demuestra.
        assertEquals("   ", provider.translate("   ", "zh", "es"));
        assertEquals(null, provider.translate(null, "zh", "es"));
    }

    @Test
    @DisplayName("Sin clave NO devuelve el original: guardaría el chino en el campo del español")
    void sinClaveFallaEnVezDeDevolverElOriginal() {
        ReflectionTestUtils.setField(provider, "apiKey", "");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> provider.translate("春装外套", "zh", "es"));

        assertTrue(e.getMessage().contains("api-key"));
    }

    @Test
    @DisplayName("De la respuesta se saca solo la traducción")
    void extraeLaTraduccion() {
        String json = """
                {"choices":[{"message":{"role":"assistant","content":"Chaqueta de primavera"}}]}""";

        assertEquals("Chaqueta de primavera", provider.extraer(json, "春装外套"));
    }

    @Test
    @DisplayName("Una respuesta vacía es un error, no una traducción en blanco")
    void respuestaVacia() {
        String json = """
                {"choices":[{"message":{"role":"assistant","content":""}}]}""";

        assertThrows(IllegalStateException.class, () -> provider.extraer(json, "春装外套"));
    }

    @Test
    @DisplayName("Una respuesta ilegible falla en vez de colar basura en la ficha")
    void respuestaIlegible() {
        assertThrows(IllegalStateException.class, () -> provider.extraer("no soy json", "春装外套"));
    }

    @Test
    @DisplayName("Las comillas que a veces añade el modelo se quitan")
    void quitaLasComillasDelModelo() {
        assertEquals("Chaqueta de primavera", provider.limpiar("\"Chaqueta de primavera\""));
        assertEquals("Chaqueta de primavera", provider.limpiar("«Chaqueta de primavera»"));
        assertEquals("Chaqueta de primavera", provider.limpiar("  Chaqueta de primavera  "));
    }

    @Test
    @DisplayName("Una comilla suelta al final NO se recorta: puede ser parte del título")
    void noRecortaComillaSuelta() {
        assertEquals("Talla 5\"", provider.limpiar("Talla 5\""));
    }
}
