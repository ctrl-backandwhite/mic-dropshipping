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
 *   <li>cada línea convierte su precio UNITARIO y lo multiplica por la cantidad —no se convierte el
 *       importe de la línea ya multiplicado—, porque es así como el cliente lee y comprueba el precio;
 *   <li>envío, impuestos y descuento se convierten por separado;
 *   <li>el total suma los componentes YA redondeados: subtotal − descuento + envío + impuestos.
 * </ol>
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

    /** Suma de las líneas: precio unitario convertido × cantidad. */
    private BigDecimal subtotalOf(Order order, String currency) {
        List<OrderItem> items = order.getItems();
        if (items == null || items.isEmpty()) {
            return convert(order.getSubtotalCents(), currency);
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (OrderItem item : items) {
            sum = sum.add(convert(item.getUnitPriceCents(), currency)
                    .multiply(BigDecimal.valueOf(item.getQuantity())));
        }
        return redondear(sum, currency);
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
