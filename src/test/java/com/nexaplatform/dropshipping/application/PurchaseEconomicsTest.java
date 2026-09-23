package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.PurchaseEconomics;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Las cuentas de una compra: catálogo contra realidad.
 *
 * <p>El cambio de CNY a la divisa del pedido sale del propio pedido, y estas pruebas lo fijan: si
 * alguien lo sustituye por la tasa del día, el margen de un pedido antiguo empezaría a moverse solo.
 */
class PurchaseEconomicsTest {

    /** Línea con cambio implícito de 10 CNY por unidad de divisa: 100 CNY cuestan 10,00 €. */
    private static OrderItem line(long costCnyCents, int costCents, int unitPriceCents) {
        return OrderItem.builder().costCnyCents(costCnyCents).costCents(costCents).unitPriceCents(unitPriceCents)
                .build();
    }

    @Test
    @DisplayName("compara lo pagado con lo que decía el catálogo")
    void comparaCosteRealConElTeorico() {
        // Catálogo: 100,00 CNY por unidad, dos unidades. Pagado: 250,00 CNY más 20,00 de envío.
        PurchaseEconomics e = PurchaseEconomics.of(List.of(line(10_000L, 1_000, 3_000)), List.of(2), 25_000L, 2_000L);

        assertThat(e.expectedCostCnyCents()).isEqualTo(20_000L);
        assertThat(e.realCostCnyCents()).isEqualTo(27_000L);
        assertThat(e.varianceCnyCents()).isEqualTo(7_000L);
        assertThat(e.overBudget()).isTrue();
    }

    @Test
    @DisplayName("avisa de que no hay coste real mientras nadie lo registre")
    void sinCosteRegistradoNoHayComparacion() {
        PurchaseEconomics e = PurchaseEconomics.of(List.of(line(10_000L, 1_000, 3_000)), List.of(1), null, null);

        assertThat(e.realCostCnyCents()).isNull();
        assertThat(e.varianceCnyCents()).isNull();
        assertThat(e.realMarginCents()).isNull();
        assertThat(e.realMarginPct()).isNull();
        assertThat(e.overBudget()).isFalse();
        // El lado teórico sí se conoce desde el primer día: es lo que se prometió al vender.
        assertThat(e.expectedMarginCents()).isEqualTo(2_000L);
    }

    @Test
    @DisplayName("vale con que conste uno de los dos importes")
    void soloMercanciaOSoloEnvio() {
        List<OrderItem> lines = List.of(line(10_000L, 1_000, 3_000));

        assertThat(PurchaseEconomics.of(lines, List.of(1), 9_000L, null).realCostCnyCents()).isEqualTo(9_000L);
        // Que el proveedor no cobre envío no puede tirar por tierra toda la comparación.
        assertThat(PurchaseEconomics.of(lines, List.of(1), null, 500L).realCostCnyCents()).isEqualTo(500L);
    }

    @Test
    @DisplayName("el margen real usa el cambio del pedido, no el de hoy")
    void margenRealConElCambioDelPedido() {
        // 100,00 CNY = 10,00 € en este pedido. Se pagaron 120,00 CNY, o sea 12,00 €.
        PurchaseEconomics e = PurchaseEconomics.of(List.of(line(10_000L, 1_000, 3_000)), List.of(1), 12_000L, null);

        assertThat(e.revenueCents()).isEqualTo(3_000L);
        assertThat(e.expectedMarginCents()).isEqualTo(2_000L);
        assertThat(e.realMarginCents()).isEqualTo(1_800L);
        assertThat(e.realMarginPct()).isEqualTo(60);
    }

    @Test
    @DisplayName("suma varias líneas con sus cantidades")
    void variasLineas() {
        PurchaseEconomics e = PurchaseEconomics.of(List.of(line(10_000L, 1_000, 3_000), line(5_000L, 500, 1_500)),
                List.of(2, 3), 30_000L, null);

        assertThat(e.expectedCostCnyCents()).isEqualTo(35_000L);
        assertThat(e.revenueCents()).isEqualTo(10_500L);
        // Se pagó menos de lo previsto: el desvío es negativo y no hay exceso.
        assertThat(e.varianceCnyCents()).isEqualTo(-5_000L);
        assertThat(e.overBudget()).isFalse();
    }

    @Test
    @DisplayName("cuenta solo las unidades que entran en esta compra")
    void cantidadParcial() {
        // La línea es de 10 unidades pero este proveedor solo sirve 3: el resto es otro bulto.
        PurchaseEconomics e = PurchaseEconomics.of(List.of(line(10_000L, 1_000, 3_000)), List.of(3), null, null);

        assertThat(e.expectedCostCnyCents()).isEqualTo(30_000L);
        assertThat(e.revenueCents()).isEqualTo(9_000L);
    }

    @Test
    @DisplayName("sin coste en CNY no inventa un cambio")
    void sinCambioDerivable() {
        // Un producto cargado sin coste en CNY no permite deducir el cambio; dar un margen real ahí
        // sería inventárselo.
        PurchaseEconomics e = PurchaseEconomics.of(List.of(line(0L, 1_000, 3_000)), List.of(1), 5_000L, null);

        assertThat(e.realMarginCents()).isNull();
        assertThat(e.realMarginPct()).isNull();
        assertThat(e.varianceCnyCents()).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("aguanta listas descuadradas y cantidades nulas sin reventar")
    void listasDescuadradas() {
        List<OrderItem> lines = List.of(line(10_000L, 1_000, 3_000), line(5_000L, 500, 1_500));

        // Menos cantidades que líneas: se procesa lo que se puede emparejar.
        PurchaseEconomics e = PurchaseEconomics.of(lines, List.of(1), null, null);
        assertThat(e.expectedCostCnyCents()).isEqualTo(10_000L);

        PurchaseEconomics conNulo = PurchaseEconomics.of(lines, java.util.Arrays.asList(null, 2), null, null);
        assertThat(conNulo.expectedCostCnyCents()).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("no divide por cero cuando no se cobró nada")
    void sinIngresoNoHayPorcentaje() {
        PurchaseEconomics e = PurchaseEconomics.of(List.of(line(10_000L, 1_000, 0)), List.of(1), 9_000L, null);

        assertThat(e.revenueCents()).isZero();
        assertThat(e.realMarginPct()).isNull();
        assertThat(e.realMarginCents()).isEqualTo(-900L);
    }

    @Test
    @DisplayName("una compra sin líneas no rompe las cuentas")
    void sinLineas() {
        PurchaseEconomics e = PurchaseEconomics.of(List.of(), List.of(), 5_000L, null);

        assertThat(e.expectedCostCnyCents()).isZero();
        assertThat(e.revenueCents()).isZero();
        assertThat(e.realCostCnyCents()).isEqualTo(5_000L);
        assertThat(e.realMarginCents()).isNull();
    }
}
