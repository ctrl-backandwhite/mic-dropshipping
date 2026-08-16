package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.model.OrderItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Las cuentas de una compra a proveedor: lo que el catálogo decía que costaría frente a lo que
 * costó de verdad, y el margen que queda después.
 *
 * <p>Hasta que el admin registra el coste pagado, el margen de un pedido es una estimación: el
 * proveedor cambia precios en 1688 y el envío nacional no está en el catálogo. Aquí se cruzan los
 * dos lados para que el desvío se vea.
 *
 * <p>La conversión de CNY a la divisa del pedido usa el cambio del PROPIO pedido, no el de hoy: cada
 * línea guarda su coste en las dos monedas ({@code costCents} y {@code costCnyCents}), y su cociente
 * es el cambio que se aplicó al vender. Con la tasa actual, el margen de un pedido de hace meses
 * cambiaría cada día sin que nadie hubiera comprado ni vendido nada.
 *
 * <p>Todos los importes van en céntimos, como en el resto del dominio.
 */
public record PurchaseEconomics(
        /** Coste de la mercancía según el catálogo, en céntimos de CNY. */
        long expectedCostCnyCents,
        /** Mercancía y envío nacional realmente pagados, en céntimos de CNY; null si aún no consta. */
        Long realCostCnyCents,
        /** Lo pagado de más (positivo) o de menos (negativo) frente al catálogo; null si no consta. */
        Long varianceCnyCents,
        /** Lo cobrado al cliente por las líneas de esta compra, en la divisa del pedido. */
        long revenueCents,
        /** Margen que se esperaba al vender, en la divisa del pedido. */
        long expectedMarginCents,
        /** Margen con el coste real; null mientras no se conozca el coste o falte el cambio. */
        Long realMarginCents) {

    /**
     * Cruza las líneas de la compra con lo que se pagó.
     *
     * @param lines      líneas del pedido cubiertas por esta compra
     * @param quantities unidades de cada línea que entran en esta compra, en el mismo orden
     * @param paidCnyCents  mercancía pagada al proveedor, o null
     * @param shippingCnyCents envío nacional pagado, o null
     */
    public static PurchaseEconomics of(List<OrderItem> lines, List<Integer> quantities,
                                       Long paidCnyCents, Long shippingCnyCents) {
        long expectedCny = 0L;
        long expectedInCurrency = 0L;
        long revenue = 0L;
        int size = Math.min(lines.size(), quantities.size());
        for (int i = 0; i < size; i++) {
            OrderItem item = lines.get(i);
            int qty = quantities.get(i) == null ? 0 : quantities.get(i);
            expectedCny += item.getCostCnyCents() * qty;
            expectedInCurrency += (long) item.getCostCents() * qty;
            revenue += (long) item.getUnitPriceCents() * qty;
        }

        Long realCny = totalPaid(paidCnyCents, shippingCnyCents);
        Long variance = realCny == null ? null : realCny - expectedCny;
        long expectedMargin = revenue - expectedInCurrency;

        return new PurchaseEconomics(expectedCny, realCny, variance, revenue, expectedMargin,
                realMargin(revenue, realCny, expectedCny, expectedInCurrency));
    }

    /**
     * Suma mercancía y envío. Basta con que conste uno de los dos: registrar solo la mercancía es
     * frecuente cuando el proveedor no cobra envío, y descartar el dato por eso sería perder la
     * comparación entera.
     */
    private static Long totalPaid(Long paidCnyCents, Long shippingCnyCents) {
        if (paidCnyCents == null && shippingCnyCents == null) {
            return null;
        }
        return (paidCnyCents == null ? 0L : paidCnyCents)
                + (shippingCnyCents == null ? 0L : shippingCnyCents);
    }

    /** Sin coste real o sin cambio derivable del pedido no hay margen real que enseñar. */
    private static Long realMargin(long revenue, Long realCny, long expectedCny,
                                   long expectedInCurrency) {
        if (realCny == null || expectedCny <= 0L) {
            return null;
        }
        BigDecimal rate = BigDecimal.valueOf(expectedInCurrency)
                .divide(BigDecimal.valueOf(expectedCny), 10, RoundingMode.HALF_UP);
        long realInCurrency = BigDecimal.valueOf(realCny).multiply(rate)
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
        return revenue - realInCurrency;
    }

    /** Margen real sobre lo cobrado, en porcentaje entero; null si no hay margen real o no se cobró. */
    public Integer realMarginPct() {
        if (realMarginCents == null || revenueCents <= 0L) {
            return null;
        }
        return BigDecimal.valueOf(realMarginCents * 100L)
                .divide(BigDecimal.valueOf(revenueCents), 0, RoundingMode.HALF_UP).intValue();
    }

    /** Cierto cuando lo pagado supera lo que decía el catálogo, que es lo que hay que mirar. */
    public boolean overBudget() {
        return varianceCnyCents != null && varianceCnyCents > 0L;
    }
}
