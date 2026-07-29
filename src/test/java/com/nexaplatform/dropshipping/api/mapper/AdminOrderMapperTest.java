package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.AdminOrderDetailDtoOut;
import com.nexaplatform.dropshipping.domain.model.Order;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mapeo del detalle de pedido que ve el ADMIN.
 *
 * <p>El caso cubierto salió al validar el seguimiento de un envío real: la ficha mostraba como "número de
 * seguimiento" el {@code externalOrderId} del pedido ({@code ME-1784936692}), distinto del número que el
 * transportista devuelve y que aparece en el bloque de seguimiento y en el correo al cliente. Dos números
 * distintos para la misma cosa hacen que el admin dé al cliente una referencia que no existe en el carrier.
 */
class AdminOrderMapperTest {

    private final AdminOrderMapper mapper = Mappers.getMapper(AdminOrderMapper.class);

    @Test
    void elNumeroDeSeguimientoEsElDelTransportistaNoLaReferenciaDelPedido() {
        Order order = new Order();
        order.setOrderNumber("NX-1784936692-7159");
        order.setExternalOrderId("ME-1784936692");
        order.setTrackingNumber("YT2621101299000012");

        AdminOrderDetailDtoOut detail = mapper.toDetail(order);

        assertThat(detail.getTrackingNumber()).isEqualTo("YT2621101299000012");
    }

    @Test
    void sinEnvioCreadoNoSeInventaUnNumeroDeSeguimiento() {
        Order order = new Order();
        order.setOrderNumber("NX-1784936692-7159");
        order.setExternalOrderId("ME-1784936692");

        assertThat(mapper.toDetail(order).getTrackingNumber()).isNull();
    }
}
