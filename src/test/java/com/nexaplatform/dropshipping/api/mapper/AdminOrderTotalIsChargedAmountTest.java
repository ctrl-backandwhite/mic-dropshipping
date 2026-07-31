package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.application.service.OrderAmounts;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * El total que ve el administrador es el que se le cobró al cliente.
 *
 * <p>Se recomponía sumando líneas, envío e impuestos, convirtiendo y redondeando cada componente por
 * separado. Con el pedido real que lo destapó —714 + 625 + 281 = 1.620 céntimos USD— el cliente pagó
 * 14,20 € y el panel enseñaba 14,21 €. Un céntimo, sí, pero en la pantalla desde la que se atiende una
 * reclamación: si el importe del panel no es el del cargo, no sirve para contrastar nada.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("El total del pedido en el panel es el importe cobrado")
class AdminOrderTotalIsChargedAmountTest {

    /** Tasa USD→EUR real del entorno: la que destapa el descuadre de un céntimo. */
    private static final BigDecimal USD_A_EUR = new BigDecimal("0.87717");

    @Mock
    private CurrencyRateService currencyRateService;

    private AdminOrderMapper mapper;

    @BeforeEach
    void setUp() {
        // La implementación que genera MapStruct: totalFormatted vive en la clase abstracta, pero
        // instanciarla a mano obligaría a implementar todos los métodos de mapeo.
        mapper = new AdminOrderMapperImpl();
        mapper.setCurrencyRateService(currencyRateService);
        mapper.setOrderAmounts(new OrderAmounts(currencyRateService));
        CurrencyHolder.set("EUR");

        when(currencyRateService.usdTo(any(BigDecimal.class), anyString()))
                .thenAnswer(inv -> inv.<BigDecimal>getArgument(0).multiply(USD_A_EUR));
        when(currencyRateService.decimalsOf(anyString())).thenReturn(2);
        when(currencyRateService.formatDisplay(any(BigDecimal.class), anyString()))
                .thenAnswer(inv -> inv.<BigDecimal>getArgument(0).setScale(2, RoundingMode.HALF_UP) + " €");
    }

    @AfterEach
    void tearDown() {
        CurrencyHolder.clear();
    }

    @Test
    void elTotalDelPanelEsElMismoQueVeYPagaElCliente() {
        Order pedido = pedidoDeLaCertificacion();

        String mostrado = mapper.totalFormatted(pedido);

        // 6,26 + 5,48 + 2,46 = 14,20 €, la cifra de la ficha del cliente y del cargo. Convertir el total
        // canónico (16,20 USD × 0,87717 = 14,2101) de una sola vez daría 14,21 €: un céntimo de más justo
        // en la pantalla desde la que se atiende una reclamación.
        assertThat(mostrado).isEqualTo("14.20 €");
    }

    @Test
    void unPedidoSinLineasCargadasSigueMostrandoSuTotal() {
        // La ficha del panel no siempre trae las líneas cargadas; recorriéndolas, el total salía a cero.
        Order pedido = pedidoDeLaCertificacion();
        pedido.setItems(null);

        assertThat(mapper.totalFormatted(pedido)).isEqualTo("14.20 €");
    }

    @Test
    void elSubtotalSeSumaLineaALineaComoLoHaceElCliente() {
        // 4 uds de 16,98 USD: la unidad convertida da 14,89 € y cuatro son 59,56 €, lo que el cliente vio
        // y pagó. Convertir los 67,92 USD de una vez daba 59,58 € y el total del panel se iba a 76,68 €
        // frente a los 76,66 € cobrados.
        Order pedido = Order.builder()
                .subtotalCents(6792).shippingCents(1112).taxCents(1517).discountCents(679).totalCents(8742)
                .currency("USD")
                .items(List.of(OrderItem.builder().unitPriceCents(1698).quantity(4).build()))
                .build();

        assertThat(mapper.totalFormatted(pedido)).isEqualTo("76.66 €");
    }

    @Test
    void elDescuentoDeReferidoSeRestaDelTotal() {
        Order pedido = pedidoDeLaCertificacion();
        pedido.setDiscountCents(100);

        // 14,20 − 0,88 (1,00 USD convertido) = 13,32 €.
        assertThat(mapper.totalFormatted(pedido)).isEqualTo("13.32 €");
    }

    /** El pedido NX-1785433008-4345: 2 uds a 3,57 $ + 6,25 $ de envío + 2,81 $ de impuestos. */
    private static Order pedidoDeLaCertificacion() {
        return Order.builder()
                .subtotalCents(714)
                .shippingCents(625)
                .taxCents(281)
                .discountCents(0)
                .totalCents(1620)
                .currency("USD")
                .items(List.of(OrderItem.builder().unitPriceCents(357).quantity(2).build()))
                .build();
    }
}
