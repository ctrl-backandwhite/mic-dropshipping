package com.nexaplatform.dropshipping.application.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reproduce un carrito real que dio un resultado sospechoso en pantalla: con el paquete ya partido
 * en dos y sitio de sobra en el segundo, el catálogo seguía anunciando 3 EUR de arancel por cada
 * producto que se añadiera.
 *
 * <p>Aquí se mira solo el repartidor: si con una unidad más siguen siendo dos bultos, el problema no
 * está en el reparto y hay que buscarlo en quien calcula el derecho.
 */
class BultosDelCarritoRealTest {

    private static final int TOPE_GRAMOS = 2000;

    private static List<ParcelSplitter.Unit> unidades(int... pesos) {
        List<ParcelSplitter.Unit> lista = new ArrayList<>();
        for (int i = 0; i < pesos.length; i++) {
            lista.add(new ParcelSplitter.Unit(i, pesos[i], 1000, 0, 0, 0, false));
        }
        return lista;
    }

    private static int bultos(int... pesos) {
        return ParcelSplitter.split(unidades(pesos), new ParcelSplitter.Limits(TOPE_GRAMOS, 0, 0)).size();
    }

    @Test
    @DisplayName("El carrito de 2.560 g viaja en dos bultos, con hueco de sobra en el segundo")
    void carritoActual() {
        // 400 + 1000 + 260 + 300 x3 = 2.560 g
        assertEquals(2, bultos(400, 1000, 260, 300, 300, 300));
    }

    @Test
    @DisplayName("Una unidad más de 300 g cabe en el bulto que ya está abierto: siguen siendo dos")
    void unaMasSigueCabiendo() {
        assertEquals(2, bultos(400, 1000, 260, 300, 300, 300, 300));
    }

    @Test
    @DisplayName("Hasta 4.000 g no hace falta un tercer bulto")
    void elTercerBultoLlegaAlSuperarElDoble() {
        assertEquals(2, bultos(1000, 1000, 1000, 1000));
        assertEquals(3, bultos(1000, 1000, 1000, 1000, 300));
    }
}
