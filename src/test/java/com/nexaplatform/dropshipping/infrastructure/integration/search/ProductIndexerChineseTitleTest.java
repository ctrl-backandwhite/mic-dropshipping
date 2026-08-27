package com.nexaplatform.dropshipping.infrastructure.integration.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El campo {@code titleZh} del índice solo puede recibir chino de verdad.
 *
 * <p>Por qué existe este test: la columna {@code product.title_zh} es el título de ORIGEN y hace de reserva
 * cuando un producto no tiene traducción, así que en el catálogo real 4.791 de 5.659 referencias activas
 * guardan ahí un título en español. Ese texto acababa en un campo con analizador {@code cjk}, que no conoce
 * las palabras vacías españolas: cada "de", "con" o "para" producía unas 4.300 coincidencias con peso 3 y
 * hundía al producto que se buscaba. Buscar «Blazer de» devolvía 4.368 resultados con el blazer en tercera
 * posición.
 *
 * <p>Si alguien vuelve a indexar el campo sin filtrar, estos casos fallan.
 */
class ProductIndexerChineseTitleTest {

    @ParameterizedTest(name = "«{0}» es chino → se indexa")
    @ValueSource(strings = {
            "单扣长袖女士西装外套",
            "女款小狗刺绣爱心纽扣针织毛衣",
            "女士缎面长裙，V领长袖",          // mezcla ideogramas con latinos: sigue siendo chino
            "2024新款女装",                   // con cifras
    })
    @DisplayName("un título con ideogramas se reconoce como chino")
    void reconoceElChino(String titulo) {
        assertThat(esChino(titulo)).isTrue();
    }

    @ParameterizedTest(name = "«{0}» NO es chino → no se indexa")
    @ValueSource(strings = {
            "Blazer de mujer con botón único y manga larga",
            "Women's single-button blazer with long sleeve",
            "Blazer femme à bouton unique et manches longues",
            "Gestrickter Pullover mit Hündchen-Stickerei",
            "Vestido de tirantes con volantes para mujer",
            "   ",
    })
    @DisplayName("un título en cualquier lengua europea NO se toma por chino")
    void rechazaLoQueNoEsChino(String titulo) {
        assertThat(esChino(titulo)).isFalse();
    }

    @Test
    @DisplayName("un título nulo no revienta y no se indexa")
    void toleraElNulo() {
        assertThat(esChino(null)).isFalse();
    }

    /**
     * Invoca el método privado del indexador. Se hace por reflexión y no exponiéndolo en la API pública
     * porque es un detalle interno del indexado: lo que interesa fijar es su comportamiento, no su firma.
     */
    private static boolean esChino(String texto) {
        Boolean r = ReflectionTestUtils.invokeMethod(ProductIndexer.class, "esChino", texto);
        return Boolean.TRUE.equals(r);
    }
}
