package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Qué línea del transportista puede llevar qué mercancía.
 *
 * <p>La línea {@code FZZXR} de YunExpress es <b>solo textil en bolsa</b>. Ofrecerla para algo que no es
 * ropa termina en una guía rechazada en el almacén con el pedido ya cobrado, y el cliente esperando. Al
 * revés cuesta lo mismo: si un pedido no puede ir por ahí y no se le ofrece ninguna alternativa, no hay
 * venta.
 *
 * <p><b>Ropa se decide por la partida arancelaria</b> (decisión del dueño, 18-ago-2026): capítulos 61
 * (prendas de punto), 62 (prendas excepto punto) y 65 (sombrerería). Se usa el arancel y no la categoría
 * del catálogo porque ya es obligatorio en cada ficha —un producto sin él no se puede ni activar—, es el
 * mismo criterio que aplica el transportista en aduana, y no añade un dato más que mantener al día.
 */
class CarrierEligibilityServiceTest {

    private final CarrierEligibilityService servicio = new CarrierEligibilityService();

    /** Un producto con la partida indicada. */
    private static ProductEntity con(String hs) {
        ProductEntity p = new ProductEntity();
        p.setHsCode(hs);
        return p;
    }

    // ------------------------------------------------------------------ la línea de ropa

    @ParameterizedTest(name = "partida {0} → {1}")
    @CsvSource({
            // Capítulo 61: prendas de punto. 6109 son camisetas.
            "610910, true", "611020, true",
            // Capítulo 62: prendas excepto las de punto.
            "620342, true", "620423, true",
            // Capítulo 65: sombrerería.
            "650500, true",
            // Y lo que no es ropa, por mucho que se le parezca.
            "640399, false", "691200, false", "711719, false", "900410, false", "910211, false",})
    @DisplayName("la línea de ropa solo admite las partidas de prendas y sombrerería")
    void laLineaDeRopaSoloAdmiteRopa(String hs, boolean admitida) {
        assertThat(servicio.admiteLaLineaDeRopa(List.of(con(hs)))).isEqualTo(admitida);
    }

    @Test
    @DisplayName("un pedido que mezcla ropa con otra cosa NO puede ir por la línea de ropa")
    void elPedidoMixtoNoEsRopa() {
        List<ProductEntity> mixto = List.of(con("610910"), con("691200"));

        assertThat(servicio.admiteLaLineaDeRopa(mixto))
                .as("basta una taza en la bolsa para que el transportista rechace la guía entera").isFalse();
    }

    @Test
    @DisplayName("varias prendas distintas siguen siendo ropa")
    void variasPrendasSiguenSiendoRopa() {
        assertThat(servicio.admiteLaLineaDeRopa(List.of(con("610910"), con("620342"), con("650500")))).isTrue();
    }

    // ------------------------------------------------------------------ datos que faltan o vienen sucios

    @ParameterizedTest(name = "partida {0} sigue siendo ropa")
    @ValueSource(strings = {" 610910", "610910 ", "6109.10", "61 09 10"})
    @DisplayName("la partida se normaliza: espacios y puntos no cambian de qué capítulo es")
    void normalizaLaPartida(String hs) {
        assertThat(servicio.admiteLaLineaDeRopa(List.of(con(hs)))).isTrue();
    }

    @ParameterizedTest(name = "partida ausente: [{0}]")
    @ValueSource(strings = {"", "   ", "6"})
    @DisplayName("sin partida utilizable no se arriesga la guía: la línea de ropa se descarta")
    void sinPartidaSeDescarta(String hs) {
        assertThat(servicio.admiteLaLineaDeRopa(List.of(con(hs))))
                .as("un producto sin partida ya no se puede activar; la regla cubre los pedidos antiguos").isFalse();
    }

    @Test
    @DisplayName("partida nula: se descarta igual, sin reventar")
    void partidaNula() {
        assertThat(servicio.admiteLaLineaDeRopa(List.of(con(null)))).isFalse();
    }

    @Test
    @DisplayName("sin productos no hay nada que decidir: no se ofrece la línea restringida")
    void sinProductos() {
        assertThat(servicio.admiteLaLineaDeRopa(List.of())).isFalse();
        assertThat(servicio.admiteLaLineaDeRopa(null)).isFalse();
    }

    // ------------------------------------------------------------------ qué canales quedan

    @Test
    @DisplayName("un canal sin restricción se admite para cualquier mercancía")
    void losCanalesSinRestriccionAdmitenTodo() {
        List<ProductEntity> taza = List.of(con("691200"));

        assertThat(servicio.admiteCanal("THPHR", taza)).isTrue();
        assertThat(servicio.admiteCanal("CANAL_LIBRE", taza)).isTrue();
        assertThat(servicio.admiteCanal("FZZXR", taza))
                .as("la de ropa es la única con restricción, y por eso es la única que se pregunta").isFalse();
    }

    @Test
    @DisplayName("el canal de ropa con variante AMZ arrastra la misma restricción")
    void laVarianteAmazonDeLaLineaDeRopaTambienEstaRestringida() {
        List<ProductEntity> taza = List.of(con("691200"));

        assertThat(servicio.admiteCanal("FZZXR-AMZ", taza))
                .as("es la misma línea de ropa con otro nombre: admite lo mismo, textil y nada más").isFalse();
        assertThat(servicio.admiteCanal("FZZXR-AMZ", List.of(con("610910")))).isTrue();
    }

    @Test
    @DisplayName("el nombre del canal se compara sin distinguir mayúsculas")
    void elCanalSeComparaSinMayusculas() {
        assertThat(servicio.admiteCanal("fzzxr", List.of(con("691200")))).isFalse();
        assertThat(servicio.admiteCanal("fzzxr", List.of(con("610910")))).isTrue();
    }
}
