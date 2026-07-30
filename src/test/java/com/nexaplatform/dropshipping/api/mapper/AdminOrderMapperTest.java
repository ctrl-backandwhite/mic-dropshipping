package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.AdminOrderDetailDtoOut;
import com.nexaplatform.dropshipping.domain.model.Order;
import org.junit.jupiter.api.Test;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import org.junit.jupiter.api.BeforeEach;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Mapeo del detalle de pedido que ve el ADMIN.
 *
 * <p>El caso cubierto salió al validar el seguimiento de un envío real: la ficha mostraba como "número de
 * seguimiento" el {@code externalOrderId} del pedido ({@code ME-1784936692}), distinto del número que el
 * transportista devuelve y que aparece en el bloque de seguimiento y en el correo al cliente. Dos números
 * distintos para la misma cosa hacen que el admin dé al cliente una referencia que no existe en el carrier.
 */
class AdminOrderMapperTest {

    private AdminOrderMapper mapper;

    @BeforeEach
    void setUp() {
        // El mapper dejó de ser interfaz al pasar el formateo de importes al backend: ahora es una clase
        // abstracta con el conversor de divisa inyectado, así que se instancia su Impl generado.
        mapper = new AdminOrderMapperImpl();
        CurrencyRateService currency = mock(CurrencyRateService.class);
        lenient().when(currency.usdTo(any(BigDecimal.class), anyString())).thenAnswer(i -> i.getArgument(0));
        lenient().when(currency.formatDisplay(any(BigDecimal.class), anyString())).thenReturn("0,00 €");
        mapper.currencyRateService = currency;
    }

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
