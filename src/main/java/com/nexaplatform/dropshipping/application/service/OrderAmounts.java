package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Lo que cuesta un pedido en una moneda concreta. Es LA cuenta: la del resumen del checkout, la de la
 * ficha del cliente, la del importe que se manda a cobrar y la del panel de administración.
 *
 * <p>Existía cuatro veces, escrita por separado, y tres de esas copias se desviaron a la vez. La
 * certificación las cazó una por una: al cliente se le cobraba el descuento de referido que se le había
 * restado en pantalla; el panel enseñaba 76,68 € donde se habían cobrado 76,66 €; y en yenes la unidad
 * y la línea no cuadraban entre sí. Ninguna era un error de aritmética: era la misma cuenta hecha en
 * cuatro sitios, y cada arreglo tapaba un sitio a la vez.
 *
 * <p>El orden importa y es este:
 * <ol>
 *   <li>cada línea multiplica en DÓLARES (precio unitario canónico × cantidad) y convierte DESPUÉS: se
 *       redondea UNA sola vez, al final de la línea;
 *   <li>envío, impuestos y descuento se convierten por separado;
 *   <li>el total suma los componentes YA redondeados: subtotal − descuento + envío + impuestos.
 * </ol>
 *
 * <p>El punto 1 decía lo contrario hasta el 14-ago-2026: se convertía el unitario, se redondeaba y se
 * multiplicaba después. Parecía lo más honesto —el cliente lee el precio de la unidad y lo multiplica—
 * pero el redondeo se multiplicaba con él. Un artículo de 0,15 $ con el euro a 0,92 sale a 0,138 €, que
 * en pantalla es 0,14 €; por cien unidades eso son 14,00 €, cuando lo que se compra son 15,00 $ = 13,80 €.
 * Veinte céntimos de más, un 1,45 % sistemático que la pasarela liquidaba de verdad. Multiplicando en
 * dólares y convirtiendo al final se cobra el importe exacto.
 *
 * <p>La contrapartida es que el unitario que se muestra ya NO multiplicado da el total de la línea, así
 * que el importe de línea se publica junto al unitario ({@code 0,14 € /ud · 13,80 €}) y el subtotal es
 * la suma de esos importes de línea: lo que el cliente ve sumado sigue dando el total que paga.
 *
 * <p>Cada conversión redondea a la unidad más pequeña que existe en esa moneda (el yen no tiene
 * céntimos), de lo que ya se encarga {@link CurrencyRateService#usdTo}.
 */
@Service
@RequiredArgsConstructor
public class OrderAmounts {

    private final CurrencyRateService currencyRateService;

    /** Desglose de un pedido en una moneda, con todos los componentes ya redondeados. */
    public record Breakdown(BigDecimal subtotal, BigDecimal discount, BigDecimal shipping, BigDecimal tax,
            BigDecimal total, String currency) {
    }

    /**
     * Calcula el desglose del pedido en la moneda dada.
     *
     * <p>Cuando el pedido llega sin sus líneas cargadas —pasa en algunas vistas del panel— el subtotal
     * se toma del propio pedido: es lo mejor disponible, aunque pueda desviarse un céntimo de la suma
     * línea a línea.
     */
    public Breakdown of(Order order, String currency) {
        BigDecimal subtotal = subtotalOf(order, currency);
        BigDecimal discount = convert(order.getDiscountCents(), currency);
        BigDecimal shipping = convert(order.getShippingCents(), currency);
        BigDecimal tax = convert(order.getTaxCents(), currency);
        BigDecimal total = subtotal.subtract(discount).add(shipping).add(tax);
        return new Breakdown(subtotal, discount, shipping, tax, total, currency);
    }

    /** Sólo el total, para quien no necesita el desglose (el importe a cobrar, el listado del panel). */
    public BigDecimal totalOf(Order order, String currency) {
        return of(order, currency).total();
    }

    /** Suma de los importes de línea, cada uno redondeado una sola vez. */
    private BigDecimal subtotalOf(Order order, String currency) {
        List<OrderItem> items = order.getItems();
        if (items == null || items.isEmpty()) {
            return convert(order.getSubtotalCents(), currency);
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (OrderItem item : items) {
            sum = sum.add(lineSubtotal(item.getUnitPriceCents(), item.getQuantity(), currency));
        }
        return redondear(sum, currency);
    }

    /**
     * Importe de UNA línea en la moneda pedida: el precio unitario canónico se multiplica en dólares —con
     * precisión completa— y sólo entonces se convierte y se redondea.
     *
     * <p>Es EL sitio donde se decide el redondeo de una línea, y por eso lo llaman todos: el carrito, la
     * vista previa del checkout, la ficha del pedido, la factura y el importe que se manda a cobrar.
     * Redondear el unitario y multiplicar después inflaba el cargo hasta un 1,45 % (ver la nota de la
     * clase); tenerlo escrito una sola vez es lo que impide que un camino se vuelva a desviar del otro.
     *
     * <p>La cantidad se toma como mínimo 1: una línea del carrito con cantidad 0 o negativa es un dato
     * corrupto, y devolver un importe negativo la convertiría en dinero a favor del cliente.
     */
    public BigDecimal lineSubtotal(long unitPriceUsdCents, int quantity, String currency) {
        return convert(Math.multiplyExact(unitPriceUsdCents, Math.max(1, quantity)), currency);
    }

    /**
     * Misma cuenta, para quien tiene el precio unitario en dólares y no en céntimos (el catálogo lo
     * tarifica como {@code BigDecimal}). Se pasa a céntimos con el MISMO redondeo que usa el pedido al
     * congelar la línea, de modo que cotizar y cobrar parten del mismo número.
     */
    public BigDecimal lineSubtotal(BigDecimal unitPriceUsd, int quantity, String currency) {
        if (unitPriceUsd == null) {
            return null;
        }
        return lineSubtotal(unitPriceUsd.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact(),
                quantity, currency);
    }

    /**
     * Un importe en céntimos de dólar, convertido a la moneda pedida y redondeado a su unidad más
     * pequeña. El redondeo se aplica aquí y no se da por hecho: así el desglose cuadra sea cual sea el
     * comportamiento de la conversión, y ningún componente arrastra decimales que la moneda no tiene.
     */
    private BigDecimal convert(long cents, String currency) {
        return redondear(currencyRateService.usdTo(BigDecimal.valueOf(cents).movePointLeft(2), currency),
                currency);
    }

    private BigDecimal redondear(BigDecimal amount, String currency) {
        return amount.setScale(currencyRateService.decimalsOf(currency), RoundingMode.HALF_UP);
    }
}
