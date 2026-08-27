package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.cj;

import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.YunExpressFulfillmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Cuándo puede CJ emitir la guía de un pedido.
 *
 * <p>CJ no despacha lo que no tiene: {@code createOrderV3} exige el identificador de variante de su
 * inventario privado, y ese identificador solo existe cuando CJ ha <b>recibido y dado de alta</b> el lote
 * con nuestro SKU. Preguntárselo antes de emitir es la diferencia entre esperar a que llegue la mercancía
 * y cobrarle al cliente una guía que nunca va a salir.
 *
 * <p>No vale mirar el estado de la compra en nuestra base: puede decir «en el almacén» y CJ no tener nada
 * dado de alta, que es exactamente lo que pasa cuando el SKU se teclea mal al depositar el lote. La única
 * respuesta que sirve es la suya.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CjReadyToShipTest {

    private static final String SKU_DEPOSITADO = "NX-1001";
    private static final String SKU_QUE_FALTA = "NX-2002";

    @Mock
    private CjInventoryLookup inventario;

    @Test
    void puedeDespacharCuandoCjTieneTodaLaMercanciaDadaDeAlta() {
        when(inventario.variantIdDe(SKU_DEPOSITADO)).thenReturn(Optional.of("vid-1"));

        assertThat(cj().readyToShip(pedidoCon(SKU_DEPOSITADO))).isTrue();
    }

    @Test
    void noDespachaSiAunFaltaUnSoloArticulo() {
        // Un pedido va en un envío: con una línea sin dar de alta, la guía no se puede emitir entera.
        when(inventario.variantIdDe(SKU_DEPOSITADO)).thenReturn(Optional.of("vid-1"));
        when(inventario.variantIdDe(SKU_QUE_FALTA)).thenReturn(Optional.empty());

        assertThat(cj().readyToShip(pedidoCon(SKU_DEPOSITADO, SKU_QUE_FALTA))).isFalse();
    }

    @Test
    void noDespachaSiCjNoContesta() {
        // variantIdDe traga el fallo y devuelve vacío. Sin confirmación no se emite: emitir a ciegas es
        // justo lo que esta comprobación viene a evitar.
        when(inventario.variantIdDe(SKU_DEPOSITADO)).thenReturn(Optional.empty());

        assertThat(cj().readyToShip(pedidoCon(SKU_DEPOSITADO))).isFalse();
    }

    @Test
    void noDespachaUnPedidoSinLineas() {
        assertThat(cj().readyToShip(Order.builder().id(UUID.randomUUID()).items(List.of()).build())).isFalse();
    }

    @Test
    void noDespachaSiUnaLineaNoTieneSku() {
        Order pedido = Order.builder().id(UUID.randomUUID())
                .items(List.of(OrderItem.builder().id(UUID.randomUUID()).quantity(1).build())).build();

        assertThat(cj().readyToShip(pedido)).isFalse();
    }

    @Test
    void conElTransportistaApagadoNoSeDespachaNiSePregunta() {
        CjFulfillmentService apagado = new CjFulfillmentService(inventario, null, null, null, null);
        ReflectionTestUtils.setField(apagado, "habilitado", false);

        assertThat(apagado.readyToShip(pedidoCon(SKU_DEPOSITADO))).isFalse();
    }

    @Test
    void unTransportistaQueNoNecesitaComprobarNadaDiceQueSi() {
        // El valor por defecto de la interfaz: YunExpress recibe y reexpide, así que le basta con que la
        // mercancía vaya en camino, cosa que ya comprueba el registro de compras al proveedor.
        YunExpressFulfillmentService yunExpress = new YunExpressFulfillmentService(null, null, null,
                new CustomsDutyLinesService(null), null, null, null, null);

        assertThat(yunExpress.readyToShip(pedidoCon(SKU_DEPOSITADO))).isTrue();
    }

    private CjFulfillmentService cj() {
        CjFulfillmentService servicio = new CjFulfillmentService(inventario, null, null, null, null);
        ReflectionTestUtils.setField(servicio, "habilitado", true);
        return servicio;
    }

    private static Order pedidoCon(String... skus) {
        List<OrderItem> lineas = new ArrayList<>();
        for (String sku : skus) {
            lineas.add(OrderItem.builder().id(UUID.randomUUID()).productId(UUID.randomUUID())
                    .skuSnapshot(sku).quantity(1).unitPriceCents(1000).lineTotalCents(1000).build());
        }
        return Order.builder().id(UUID.randomUUID()).orderNumber("NX-TEST").items(List.copyOf(lineas)).build();
    }
}
