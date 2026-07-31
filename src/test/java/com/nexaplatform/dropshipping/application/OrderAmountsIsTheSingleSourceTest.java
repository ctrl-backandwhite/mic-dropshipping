package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.OrderAmounts;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CurrencyRateEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CurrencyRateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Lo que cuesta un pedido se calcula en un solo sitio.
 *
 * <p>La cuenta vivía cuatro veces —resumen del checkout, ficha del cliente, importe a cobrar y panel—
 * y tres de esas copias se desviaron a la vez. Al cliente se le cobraba el descuento de referido que se
 * le había restado en pantalla; el panel enseñaba 76,68 € donde se habían cobrado 76,66 €; y en yenes
 * la unidad y la línea no cuadraban. Ninguna era un error de aritmética: era la misma cuenta hecha en
 * cuatro sitios distintos.
 *
 * <p>Estos casos fijan la cuenta que ahora comparten todos, con las cifras del pedido que destapó cada
 * desviación.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("La cuenta del pedido vive en un solo sitio")
class OrderAmountsIsTheSingleSourceTest {

    /* Pedido de la certificación: 4 uds a 16,98 USD, con su descuento de referido del 10 %. */
    private static final int SUBTOTAL = 6792;
    private static final int DESCUENTO = 679;
    private static final int ENVIO = 1112;
    private static final int IMPUESTOS = 1517;
    private static final int TOTAL = SUBTOTAL - DESCUENTO + ENVIO + IMPUESTOS; // 8742

    @Mock
    private CurrencyRateRepository repo;

    private OrderAmounts amounts;
    private CurrencyRateService currencyRateService;

    @BeforeEach
    void setUp() {
        currencyRateService = new CurrencyRateService(repo);
        amounts = new OrderAmounts(currencyRateService);
        when(repo.findAll()).thenReturn(List.of(
                tasa("EUR", "0.87717"), tasa("JPY", "163.508502"), tasa("CLP", "934.879702"),
                tasa("GBP", "0.751900"), tasa("MXN", "17.450000")));
        currencyRateService.warm();
    }

    private static CurrencyRateEntity tasa(String code, String rate) {
        CurrencyRateEntity e = new CurrencyRateEntity();
        e.setCode(code);
        e.setRateVsUsd(new BigDecimal(rate));
        return e;
    }

    private static Order pedido() {
        return Order.builder()
                .currency("USD")
                .subtotalCents(SUBTOTAL).discountCents(DESCUENTO).shippingCents(ENVIO).taxCents(IMPUESTOS)
                .totalCents(TOTAL)
                .items(List.of(OrderItem.builder().unitPriceCents(1698).quantity(4).build()))
                .build();
    }

    @Test
    void elTotalEsElQueSeLeEnsenoYCobroAlCliente() {
        OrderAmounts.Breakdown b = amounts.of(pedido(), "EUR");

        // 14,89 × 4 = 59,56 · −5,96 · +9,75 · +13,31 = 76,66 €
        assertThat(b.subtotal()).isEqualByComparingTo("59.56");
        assertThat(b.discount()).isEqualByComparingTo("5.96");
        assertThat(b.shipping()).isEqualByComparingTo("9.75");
        assertThat(b.tax()).isEqualByComparingTo("13.31");
        assertThat(b.total()).isEqualByComparingTo("76.66");
    }

    @Test
    void elSubtotalSaleDeMultiplicarLaUnidadYaConvertidaYNoDeConvertirElTotal() {
        OrderAmounts.Breakdown b = amounts.of(pedido(), "EUR");

        BigDecimal unidad = currencyRateService.usdTo(new BigDecimal("16.98"), "EUR");
        assertThat(b.subtotal()).isEqualByComparingTo(unidad.multiply(BigDecimal.valueOf(4)));
        // Convertir los 67,92 USD de una vez daría 59,58 €: dos céntimos que llegaban hasta el total.
        assertThat(b.subtotal()).isNotEqualByComparingTo(currencyRateService.usdTo(new BigDecimal("67.92"), "EUR"));
    }

    @Test
    void elDesgloseSiempreSumaElTotal() {
        OrderAmounts.Breakdown b = amounts.of(pedido(), "EUR");

        assertThat(b.subtotal().subtract(b.discount()).add(b.shipping()).add(b.tax()))
                .isEqualByComparingTo(b.total());
    }

    @ParameterizedTest
    @ValueSource(strings = {"EUR", "JPY", "CLP", "GBP", "MXN", "USD"})
    void elDesgloseCuadraEnCualquierMoneda(String moneda) {
        OrderAmounts.Breakdown b = amounts.of(pedido(), moneda);

        assertThat(b.subtotal().subtract(b.discount()).add(b.shipping()).add(b.tax()))
                .isEqualByComparingTo(b.total());
        assertThat(b.currency()).isEqualTo(moneda);
    }

    @ParameterizedTest
    @ValueSource(strings = {"JPY", "CLP"})
    void enLasMonedasSinCentimosElSubtotalEsMultiploExactoDeLaUnidad(String moneda) {
        OrderAmounts.Breakdown b = amounts.of(pedido(), moneda);

        BigDecimal unidad = currencyRateService.usdTo(new BigDecimal("16.98"), moneda);
        assertThat(unidad.scale()).isZero();
        assertThat(b.subtotal()).isEqualByComparingTo(unidad.multiply(BigDecimal.valueOf(4)));
    }

    @Test
    void unPedidoSinLineasCargadasUsaSuPropioSubtotal() {
        // La ficha del panel no siempre trae las líneas: entonces el subtotal del pedido es lo mejor
        // disponible, y desde luego mejor que un total a cero.
        Order sinLineas = pedido();
        sinLineas.setItems(null);

        assertThat(amounts.of(sinLineas, "EUR").subtotal())
                .isEqualByComparingTo(currencyRateService.usdTo(new BigDecimal("67.92"), "EUR"));
    }

    @Test
    void unPedidoSinDescuentoNoRestaNada() {
        Order sinDescuento = pedido();
        sinDescuento.setDiscountCents(0);

        OrderAmounts.Breakdown b = amounts.of(sinDescuento, "EUR");

        assertThat(b.discount()).isEqualByComparingTo("0.00");
        assertThat(b.total()).isEqualByComparingTo(b.subtotal().add(b.shipping()).add(b.tax()));
    }
}
