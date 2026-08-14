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
import java.util.ArrayList;
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
 *
 * <p>El 14-ago-2026 cambió UNA regla: el importe de cada línea se multiplica en dólares y se convierte
 * al final, en vez de convertir el unitario y multiplicarlo después. Redondear el unitario y
 * multiplicarlo hacía crecer el redondeo con la cantidad —0,15 $ la unidad, el euro a 0,92, cien
 * unidades: 14,00 € cobrados donde había que cobrar 13,80 €—, un 1,45 % sistemático que la pasarela
 * liquidaba de verdad. Los casos de abajo comparan al céntimo contra importes calculados a mano.
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
        when(repo.findAll()).thenReturn(tabla());
        currencyRateService.warm();
    }

    /** Las tasas de la certificación. Se re-siembran enteras porque la caché sólo se recarga entera. */
    private static List<CurrencyRateEntity> tabla() {
        return List.of(tasa("EUR", "0.87717"), tasa("JPY", "163.508502"), tasa("CLP", "934.879702"),
                tasa("GBP", "0.751900"), tasa("MXN", "17.450000"));
    }

    /**
     * Deja el euro a la tasa que necesita el caso.
     *
     * <p>Los casos históricos usan la tasa del pedido que destapó cada desviación (0,87717); los del
     * redondeo de línea usan el 0,92 del ejemplo con el que se decidió la corrección, para que los
     * importes escritos a mano en el javadoc se puedan comprobar con una calculadora.
     */
    private void euroA(String tasa) {
        List<CurrencyRateEntity> conEuroPropio = new ArrayList<>(tabla());
        // La caché se rellena por código: el último gana, así que este euro sustituye al de la tabla.
        conEuroPropio.add(tasa("EUR", tasa));
        when(repo.findAll()).thenReturn(conEuroPropio);
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

    /** Pedido de sólo producto (sin envío, impuesto ni descuento) con las líneas indicadas. */
    private static Order pedidoCon(List<OrderItem> lineas) {
        int subtotal = lineas.stream().mapToInt(l -> l.getUnitPriceCents() * l.getQuantity()).sum();
        return Order.builder().currency("USD").subtotalCents(subtotal).totalCents(subtotal).items(lineas).build();
    }

    private static OrderItem linea(int unitPriceCents, int cantidad) {
        return OrderItem.builder().unitPriceCents(unitPriceCents).quantity(cantidad).build();
    }

    @Test
    void elTotalEsElQueSeLeEnsenoYCobroAlCliente() {
        OrderAmounts.Breakdown b = amounts.of(pedido(), "EUR");

        // 67,92 × 0,87717 = 59,5773... → 59,58 · −5,96 · +9,75 · +13,31 = 76,68 €
        assertThat(b.subtotal()).isEqualByComparingTo("59.58");
        assertThat(b.discount()).isEqualByComparingTo("5.96");
        assertThat(b.shipping()).isEqualByComparingTo("9.75");
        assertThat(b.tax()).isEqualByComparingTo("13.31");
        assertThat(b.total()).isEqualByComparingTo("76.68");
    }

    @Test
    void elImporteDeLineaSeConvierteYaMultiplicadoYNoUnaVezPorUnidad() {
        OrderAmounts.Breakdown b = amounts.of(pedido(), "EUR");

        // 4 × 16,98 $ = 67,92 $, y ESO es lo que se convierte: 59,58 €.
        assertThat(b.subtotal()).isEqualByComparingTo(currencyRateService.usdTo(new BigDecimal("67.92"), "EUR"));
        // Convertir el unitario y multiplicarlo después daba 14,89 × 4 = 59,56 €: dos céntimos de MENOS
        // aquí, pero el signo del error depende del precio y de la tasa, y con céntimos pequeños se
        // dispara —0,15 $ × 100 al cambio 0,92 daba 14,00 € en vez de 13,80 €, un 1,45 % de más—.
        BigDecimal unidadConvertida = currencyRateService.usdTo(new BigDecimal("16.98"), "EUR");
        assertThat(b.subtotal()).isNotEqualByComparingTo(unidadConvertida.multiply(BigDecimal.valueOf(4)));
    }

    @Test
    void cienUnidadesDeQuinceCentimosCuestanTreceOchenta() {
        // El caso exacto que motivó el cambio, con el euro a 0,92: 0,15 $ la unidad son 0,138 €, que en
        // pantalla se leen 0,14 €; por 100 unidades el cobro salía a 14,00 € cuando lo comprado son
        // 15,00 $ = 13,80 €. Veinte céntimos de más en un pedido de trece euros: un 1,45 %.
        euroA("0.92");

        OrderAmounts.Breakdown b = amounts.of(pedidoCon(List.of(linea(15, 100))), "EUR");

        assertThat(b.subtotal()).isEqualByComparingTo("13.80");
        assertThat(b.subtotal()).as("0,14 € × 100 = 14,00 € era el cobro inflado").isNotEqualByComparingTo("14.00");
    }

    @Test
    void conUnaSolaUnidadNoCambiaNada() {
        // Sin cantidad que multiplicar no hay redondeo que multiplicar: el importe de línea ES el
        // unitario convertido, 0,15 × 0,92 = 0,138 → 0,14 €. La corrección no puede mover este número.
        euroA("0.92");

        OrderAmounts.Breakdown b = amounts.of(pedidoCon(List.of(linea(15, 1))), "EUR");

        assertThat(b.subtotal()).isEqualByComparingTo("0.14");
        assertThat(b.subtotal()).isEqualByComparingTo(currencyRateService.usdTo(new BigDecimal("0.15"), "EUR"));
    }

    @Test
    void unImporteQueCaeEnMedioCentimoRedondeaHaciaArriba() {
        // Con el euro a 0,875, tres unidades de 0,20 $ son 0,60 $ × 0,875 = 0,525 € EXACTOS: medio
        // céntimo justo, el peor caso. HALF_UP lo sube a 0,53 €; truncar dejaría 0,52 y cobraría de menos.
        euroA("0.875");

        OrderAmounts.Breakdown b = amounts.of(pedidoCon(List.of(linea(20, 3))), "EUR");

        assertThat(b.subtotal()).as("0,525 € sube a 0,53, no baja a 0,52").isEqualByComparingTo("0.53");
    }

    @Test
    void variasLineasDistintasSumanExactamenteElSubtotal() {
        // Tres líneas con precios y cantidades distintos, cada una redondeada una sola vez:
        //   0,15 $ × 100 = 15,00 $ × 0,92 = 13,80    → 13,80 €
        //   1,99 $ ×   3 =  5,97 $ × 0,92 =  5,4924  →  5,49 €
        //   7,05 $ ×   7 = 49,35 $ × 0,92 = 45,402   → 45,40 €
        // Subtotal = 13,80 + 5,49 + 45,40 = 64,69 €.
        euroA("0.92");
        Order pedido = pedidoCon(List.of(linea(15, 100), linea(199, 3), linea(705, 7)));

        OrderAmounts.Breakdown b = amounts.of(pedido, "EUR");

        assertThat(b.subtotal()).isEqualByComparingTo("64.69");
        assertThat(amounts.lineSubtotal(15, 100, "EUR")
                .add(amounts.lineSubtotal(199, 3, "EUR"))
                .add(amounts.lineSubtotal(705, 7, "EUR")))
                .as("el subtotal es EXACTAMENTE la suma de los importes de línea que se enseñan")
                .isEqualByComparingTo(b.subtotal());
        // Y no es lo que daba antes: 0,14 × 100 + 1,83 × 3 + 6,49 × 7 = 14,00 + 5,49 + 45,43 = 64,92 €.
        assertThat(b.subtotal()).isNotEqualByComparingTo("64.92");
    }

    @Test
    void unaCantidadCorruptaCuentaComoUnaUnidadYNuncaResta() {
        // Una línea con cantidad 0 o negativa es un dato roto; devolver un importe negativo la
        // convertiría en dinero a favor del cliente dentro del subtotal.
        euroA("0.92");

        assertThat(amounts.lineSubtotal(15, 0, "EUR")).isEqualByComparingTo("0.14");
        assertThat(amounts.lineSubtotal(15, -5, "EUR")).isEqualByComparingTo("0.14");
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
    void enLasMonedasSinCentimosElSubtotalSaleDeConvertirLaLineaEntera(String moneda) {
        // El yen y el peso chileno no tienen decimales, así que el redondeo del unitario es de una unidad
        // ENTERA de moneda: multiplicarlo por la cantidad amplificaba el error hasta hacerlo visible
        // (4 × 2.776 ¥ = 11.104 ¥ frente a los 11.105 ¥ que valen 67,92 $). Se convierte la línea entera.
        OrderAmounts.Breakdown b = amounts.of(pedido(), moneda);

        BigDecimal unidad = currencyRateService.usdTo(new BigDecimal("16.98"), moneda);
        assertThat(unidad.scale()).isZero();
        assertThat(b.subtotal()).isEqualByComparingTo(currencyRateService.usdTo(new BigDecimal("67.92"), moneda));
        assertThat(b.subtotal()).isNotEqualByComparingTo(unidad.multiply(BigDecimal.valueOf(4)));
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
