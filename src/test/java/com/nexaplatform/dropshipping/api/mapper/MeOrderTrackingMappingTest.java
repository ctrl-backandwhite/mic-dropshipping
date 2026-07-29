package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.MeOrderDetailDtoOut;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PaymentJpaRepositoryAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Datos de seguimiento en la ficha de pedido del COMPRADOR.
 *
 * <p>El transportista y el número de guía se enviaban siempre a {@code null}, aunque la pantalla del
 * pedido tiene sitio para mostrarlos: el comprador solo podía ver su número abriendo el bloque de
 * seguimiento. Estos tests fijan que se devuelvan cuando el envío existe — y que no se inventen cuando
 * el pedido todavía no se ha despachado.
 */
class MeOrderTrackingMappingTest {

    private MeOrderDtoMapper mapper;

    @BeforeEach
    void setUp() {
        CurrencyRateService currency = Mockito.mock(CurrencyRateService.class);
        PaymentJpaRepositoryAdapter payments = Mockito.mock(PaymentJpaRepositoryAdapter.class);
        lenient().when(currency.usdTo(any(BigDecimal.class), anyString())).thenAnswer(i -> i.getArgument(0));
        lenient().when(currency.formatDisplay(any(BigDecimal.class), anyString())).thenReturn("$0.00");
        lenient().when(payments.findByOrderIdOrderByCreatedAtDesc(any(UUID.class))).thenReturn(List.of());
        mapper = new MeOrderDtoMapper(currency, payments);
    }

    private static Order despachado() {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setOrderNumber("NX-1784936692-7159");
        order.setStatus(OrderStatus.FORWARDED);
        order.setCurrency("USD");
        order.setCarrier("Standard Shipping");
        order.setTrackingNumber("YT2621101299000012");
        return order;
    }

    @Test
    void elCompradorVeTransportistaYNumeroDeSeguimiento() {
        MeOrderDetailDtoOut detail = mapper.toDetailDtoOut(despachado());

        assertThat(detail.getTrackingNumber()).isEqualTo("YT2621101299000012");
        assertThat(detail.getTrackingCarrier()).isEqualTo("Standard Shipping");
    }

    @Test
    void sinEnvioTodaviaNoHayNadaQueMostrar() {
        Order sinDespachar = despachado();
        sinDespachar.setCarrier(null);
        sinDespachar.setTrackingNumber(null);
        sinDespachar.setStatus(OrderStatus.PAID);

        MeOrderDetailDtoOut detail = mapper.toDetailDtoOut(sinDespachar);

        assertThat(detail.getTrackingNumber()).isNull();
        assertThat(detail.getTrackingCarrier()).isNull();
    }
}
