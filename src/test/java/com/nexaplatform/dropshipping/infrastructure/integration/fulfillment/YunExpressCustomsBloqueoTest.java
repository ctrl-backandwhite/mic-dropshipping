package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * La declaración incompleta NO se transmite.
 *
 * <p>Hasta ahora los huecos de la declaración solo dejaban un aviso en el registro y la guía salía igual:
 * el transportista podía rechazarla —con el pedido ya cobrado— o la aduana retener el paquete, y el fallo
 * se descubría cuando reclamaba el cliente. Ahora se corta antes de llamar al transportista.
 *
 * <p>El fallo es PERMANENTE a propósito: reintentarlo cada diez minutos no va a hacer aparecer una partida
 * arancelaria. Así el pedido cae de inmediato en la bandeja de incidencias del panel, con aviso por correo,
 * en vez de consumir tres intentos en silencio; el administrador corrige el producto y relanza el envío.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class YunExpressCustomsBloqueoTest {

    @Mock
    private ProductRepository productRepository;

    private final UUID productId = UUID.randomUUID();

    private YunExpressFulfillmentService service() {
        return new YunExpressFulfillmentService(null, null, null, new CustomsDutyLinesService(null), productRepository,
                null, null, null);
    }

    /** Producto con todo lo obligatorio; cada test le quita justo el dato que quiere provocar. */
    private ProductEntity producto() {
        ProductEntity p = new ProductEntity();
        p.setId(productId);
        p.setHsCode("9102190000");
        p.setWeightGrams(300);
        when(productRepository.findById(productId)).thenReturn(Optional.of(p));
        return p;
    }

    private Order pedidoDe(ProductEntity producto) {
        OrderItem item = new OrderItem();
        item.setProductId(producto.getId());
        item.setQuantity(1);
        item.setUnitPriceCents(1250);
        item.setSkuSnapshot("SKU-1");
        item.setTitleSnapshot("Men's quartz watch");
        item.setProductTitleZh("男士石英手表");
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setOrderNumber("NX-1");
        order.setCurrency("USD");
        order.setShippingCountry("ES");
        order.setItems(List.of(item));
        return order;
    }

    @Test
    @DisplayName("sin partida arancelaria no se llega a llamar al transportista")
    void sinPartidaArancelariaNoSeLlamaAlTransportista() {
        ProductEntity p = producto();
        p.setHsCode(null);
        Order pedido = pedidoDe(p);

        assertThatThrownBy(() -> service().createShipments(pedido)).isInstanceOf(FulfillmentFailure.class)
                .hasMessageContaining("partida arancelaria (HSCode)").hasMessageContaining("NX-1")
                .hasMessageContaining("SKU-1");
    }

    @Test
    @DisplayName("el bloqueo es permanente: reintentarlo no hará aparecer el dato que falta")
    void elBloqueoEsPermanente() {
        ProductEntity p = producto();
        p.setHsCode(null);
        Order pedido = pedidoDe(p);

        FulfillmentFailure fallo = (FulfillmentFailure) org.assertj.core.api.Assertions
                .catchThrowable(() -> service().createShipments(pedido));

        assertThat(fallo.isPermanent()).isTrue();
    }

    @Test
    @DisplayName("el aviso enumera TODOS los datos que faltan, no solo el primero")
    void elAvisoEnumeraTodosLosDatosQueFaltan() {
        // Corregirlos de uno en uno obligaría al administrador a relanzar el envío por cada campo.
        ProductEntity p = producto();
        p.setHsCode(null);
        p.setWeightGrams(null);
        Order pedido = pedidoDe(p);
        pedido.getItems().get(0).setProductTitleZh("Reloj de pulsera"); // sin ideogramas: cuenta como ausente

        assertThatThrownBy(() -> service().createShipments(pedido)).hasMessageContaining("partida arancelaria (HSCode)")
                .hasMessageContaining("peso unitario (UnitWeight)").hasMessageContaining("nombre en chino (CName)");
    }

    @Test
    @DisplayName("una declaración completa no bloquea el envío")
    void unaDeclaracionCompletaNoBloqueaElEnvio() {
        Order pedido = pedidoDe(producto());

        assertThatCode(() -> service().requireCompleteCustoms(pedido)).doesNotThrowAnyException();
    }
}
