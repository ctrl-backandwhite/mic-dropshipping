package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService.DutyParcel;
import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService.Line;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Derecho temporal de 3 EUR de la Unión Europea (Reglamento (UE) 2026/382, en vigor del 1-jul-2026 al
 * 1-jul-2028).
 *
 * <p>Lo que se protege aquí es dinero real en las dos direcciones: cobrar de más al cliente por artículos
 * que la aduana cuenta como UNA sola línea, o cobrar de menos y pagar nosotros la diferencia al despachar.
 * La regla que se comprueba es la de la guía de la Comisión: el derecho «will automatically apply per
 * declaration line irrespective of the quantity (number of the articles) in that declaration line», siendo
 * una línea el conjunto de mercancías que comparten clasificación arancelaria.
 */
class CustomsDutyLinesServiceTest {

    private CustomsDutyLinesService service;

    @BeforeEach
    void sinLimitesDeBulto() {
        service = new CustomsDutyLinesService();
        // 0 = sin límite: todo viaja en un bulto. Los límites reales del canal se prueban aparte.
        ReflectionTestUtils.setField(service, "maxParcelWeightGrams", 0);
        ReflectionTestUtils.setField(service, "maxParcelValueCents", 0);
        ReflectionTestUtils.setField(service, "maxParcelUnits", 0);
    }

    private static Line linea(String hs, int cantidad) {
        return new Line(UUID.randomUUID(), hs, cantidad, 1000, 500, 0, 0, 0, false);
    }

    /**
     * El pedido tipo de la tienda: cinco vaqueros, tres camisetas, unos zapatos, tres bóxer y un abrigo,
     * cada familia con su clasificación. Son CINCO líneas de declaración → 5 × 3 EUR = 15 EUR.
     * Contando artículos serían trece → 39 EUR, es decir 24 EUR de más al cliente en un solo pedido.
     */
    @Test
    void unPedidoDeCincoFamiliasPagaCincoLineas() {
        List<DutyParcel> bultos = service.parcelsOf(List.of(
                linea("620342", 5),   // vaqueros
                linea("610910", 3),   // camisetas
                linea("640399", 1),   // zapatos
                linea("610711", 3),   // bóxer
                linea("620293", 1))); // abrigo

        assertThat(bultos).hasSize(1);
        assertThat(bultos.get(0).tariffLines()).isEqualTo(5);
    }

    /** La cantidad NO multiplica: cinco unidades de la misma referencia son una línea, no cinco. */
    @Test
    void laCantidadNoMultiplicaElDerecho() {
        assertThat(service.parcelsOf(List.of(linea("620342", 5))).get(0).tariffLines()).isEqualTo(1);
        assertThat(service.parcelsOf(List.of(linea("620342", 1))).get(0).tariffLines()).isEqualTo(1);
    }

    /**
     * Ejemplo literal de la guía de la Comisión: un anorak, un cortavientos y una cazadora de tres
     * referencias DISTINTAS que comparten la subpartida 6104 19 son UNA línea (3 EUR), no tres (9 EUR).
     * Es justo el caso que antes se cobraba de más, porque se contaban productos distintos.
     */
    @Test
    void productosDistintosConLaMismaSubpartidaSonUnaSolaLinea() {
        List<DutyParcel> bultos = service.parcelsOf(List.of(
                linea("610419", 1), linea("610419", 1), linea("610419", 1)));

        assertThat(bultos.get(0).tariffLines()).isEqualTo(1);
    }

    /** Se agrupa por los 6 primeros dígitos (subpartida del H7), aunque el código venga con más detalle. */
    @Test
    void seAgrupaPorLosSeisPrimerosDigitos() {
        List<DutyParcel> bultos = service.parcelsOf(List.of(
                linea("6104199010", 1), linea("610419 90 20", 1), linea("6104.19.90.90", 1)));

        assertThat(bultos.get(0).tariffLines()).isEqualTo(1);
    }

    /**
     * Sin clasificación no se puede agrupar: cada producto cuenta como línea propia. Se cobra de más en el
     * peor caso, nunca de menos — una línea sin cubrir la pagaríamos nosotros en el despacho.
     */
    @Test
    void unProductoSinClasificacionCuentaComoLineaPropia() {
        List<DutyParcel> bultos = service.parcelsOf(List.of(
                linea(null, 1), linea("", 2), linea("123", 1), linea("620342", 4)));

        assertThat(bultos.get(0).tariffLines()).isEqualTo(4);
    }

    /** Un carrito vacío no declara nada: no hay envío y no hay derecho que cobrar. */
    @Test
    void sinLineasNoHayBultosQueDeclarar() {
        assertThat(service.parcelsOf(List.of())).isEmpty();
        assertThat(service.parcelsOf(null)).isEmpty();
    }

    /**
     * Cuando el transportista parte la mercancía en varios bultos, cada uno lleva SU declaración: las
     * líneas se cuentan dentro de cada bulto, no una sola vez para todo el pedido.
     */
    @Test
    void conVariosBultosCadaUnoDeclaraSusPropiasLineas() {
        ReflectionTestUtils.setField(service, "maxParcelUnits", 2);

        List<DutyParcel> bultos = service.parcelsOf(List.of(linea("620342", 2), linea("610910", 2)));

        assertThat(bultos).hasSizeGreaterThan(1);
        assertThat(bultos.stream().mapToInt(DutyParcel::tariffLines).sum()).isGreaterThanOrEqualTo(2);
    }
}
