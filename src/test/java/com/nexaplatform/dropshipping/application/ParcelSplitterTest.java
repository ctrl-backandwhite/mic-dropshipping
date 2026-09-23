package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.ParcelSplitter;
import com.nexaplatform.dropshipping.application.service.ParcelSplitter.Bin;
import com.nexaplatform.dropshipping.application.service.ParcelSplitter.Limits;
import com.nexaplatform.dropshipping.application.service.ParcelSplitter.Unit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reparto de un pedido en los bultos que admite el canal del transportista.
 *
 * <p>El caso real que lo motiva: un pedido de 11,85 kg y 70,21 $ que el canal BPA rechazó entero porque
 * no admite más de 2 kg ni más de 24 $. Repartido en bultos que sí cumplen, el pedido sale.
 *
 * <p>Lo que se protege aquí es que <b>ningún bulto exceda los límites</b> (salvo un artículo que ya los
 * exceda por sí solo, que no se puede partir) y que no se generen bultos de más, porque cada bulto es una
 * guía y un coste de envío.
 */
class ParcelSplitterTest {

    private static Unit unit(int line, int grams, int cents) {
        return new Unit(line, grams, cents, 200, 150, 50, false);
    }

    private static List<Unit> units(int count, int grams, int cents) {
        List<Unit> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(unit(i, grams, cents));
        }
        return list;
    }

    @Test
    void sinLimitesTodoViajaEnUnSoloBulto() {
        List<Bin> bins = ParcelSplitter.split(units(10, 900, 1500), new Limits(0, 0, 0));

        assertThat(bins).hasSize(1);
        assertThat(bins.get(0).spec().weightGrams()).isEqualTo(9000);
    }

    @Test
    void reparteCuandoSeExcedeElPesoDelCanal() {
        // 6 unidades de 900 g con tope de 2 kg -> caben 2 por bulto (1800 g), es decir 3 bultos.
        List<Bin> bins = ParcelSplitter.split(units(6, 900, 100), new Limits(2000, 0, 0));

        assertThat(bins).hasSize(3).allSatisfy(b -> assertThat(b.spec().weightGrams()).isLessThanOrEqualTo(2000));
    }

    @Test
    void reparteCuandoSeExcedeElValorDeclarado() {
        // 4 unidades de 10 $ con tope de 24 $ -> 2 por bulto.
        List<Bin> bins = ParcelSplitter.split(units(4, 100, 1000), new Limits(0, 2400, 0));

        assertThat(bins).hasSize(2).allSatisfy(b -> assertThat(b.valueCents()).isLessThanOrEqualTo(2400));
    }

    @Test
    void respetaAmbosLimitesALaVez() {
        List<Unit> mixed = List.of(unit(0, 1500, 500), unit(1, 600, 2000), unit(2, 300, 300), unit(3, 1900, 100));

        List<Bin> bins = ParcelSplitter.split(mixed, new Limits(2000, 2400, 0));

        assertThat(bins).allSatisfy(b -> {
            assertThat(b.spec().weightGrams()).isLessThanOrEqualTo(2000);
            assertThat(b.valueCents()).isLessThanOrEqualTo(2400);
        });
        // Ninguna unidad se pierde por el camino.
        assertThat(bins.stream().mapToInt(b -> b.units().size()).sum()).isEqualTo(4);
    }

    @Test
    void unArticuloQueNoCabeNiSoloViajaAparteYNoBloqueaAlResto() {
        List<Unit> conPesado = List.of(unit(0, 5000, 100), unit(1, 500, 100), unit(2, 500, 100));

        List<Bin> bins = ParcelSplitter.split(conPesado, new Limits(2000, 0, 0));

        // El pesado va solo; los otros dos comparten bulto.
        assertThat(bins).hasSize(2);
        assertThat(bins.stream().anyMatch(b -> b.spec().weightGrams() == 5000)).isTrue();
        assertThat(bins.stream().anyMatch(b -> b.spec().weightGrams() == 1000)).isTrue();
    }

    @Test
    void elRepartoNoPierdeNiDuplicaUnidades() {
        List<Bin> bins = ParcelSplitter.split(units(17, 700, 450), new Limits(2000, 2400, 0));

        assertThat(bins.stream().mapToInt(b -> b.units().size()).sum()).isEqualTo(17);
        assertThat(bins.stream().mapToInt(b -> b.spec().weightGrams()).sum()).isEqualTo(17 * 700);
        assertThat(bins.stream().mapToInt(Bin::valueCents).sum()).isEqualTo(17 * 450);
    }

    @Test
    void seSabeCuantasUnidadesDeCadaLineaLlevaCadaBulto() {
        List<Unit> dosLineas = List.of(unit(0, 900, 100), unit(0, 900, 100), unit(1, 900, 100));

        List<Bin> bins = ParcelSplitter.split(dosLineas, new Limits(2000, 0, 0));

        int totalLinea0 = bins.stream().mapToInt(b -> b.quantityOfLine(0)).sum();
        int totalLinea1 = bins.stream().mapToInt(b -> b.quantityOfLine(1)).sum();
        assertThat(totalLinea0).isEqualTo(2);
        assertThat(totalLinea1).isEqualTo(1);
    }

    @Test
    void unPedidoVacioDevuelveUnBultoParaNoRomperElFlujo() {
        List<Bin> bins = ParcelSplitter.split(List.of(), new Limits(2000, 2400, 0));

        assertThat(bins).hasSize(1);
        assertThat(bins.get(0).units()).isEmpty();
    }

    @Test
    void tambienSePuedeLimitarPorNumeroDeArticulos() {
        List<Bin> bins = ParcelSplitter.split(units(5, 100, 100), new Limits(0, 0, 2));

        assertThat(bins).hasSize(3).allSatisfy(b -> assertThat(b.units()).hasSizeLessThanOrEqualTo(2));
    }

    @Test
    void elCasoRealDelPedidoRechazadoSeResuelveEnVariosBultos() {
        // Pedido de 11,853 kg y 70,21 $ que BPA rechazó entero (máx 2 kg / 24 $).
        List<Unit> pedido = List.of(unit(0, 4000, 2500), unit(1, 300, 1200), unit(2, 300, 1200), unit(3, 7253, 3121));

        List<Bin> bins = ParcelSplitter.split(pedido, new Limits(2000, 2400, 0));

        assertThat(bins).isNotEmpty();
        // Los dos artículos que sí caben quedan dentro de límites; los que no, viajan solos.
        assertThat(bins.stream().filter(b -> b.spec().weightGrams() <= 2000))
                .allSatisfy(b -> assertThat(b.valueCents()).isLessThanOrEqualTo(2400));
        assertThat(bins.stream().mapToInt(b -> b.units().size()).sum()).isEqualTo(4);
    }
}
