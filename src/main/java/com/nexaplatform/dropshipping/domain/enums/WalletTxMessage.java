package com.nexaplatform.dropshipping.domain.enums;

/**
 * Descripciones de los movimientos del wallet, traducidas a los 8 idiomas soportados. Las descripciones se
 * guardan en inglés al crear la transacción; se localizan al LEER (mapper) según el idioma activo. Los
 * marcadores {@code {method}} (método de pago ya localizado) y {@code {order}} (nº de pedido) se sustituyen
 * al renderizar. Mismo patrón que {@link OrderEmailLabel}/{@link InvoiceLabel}.
 */
public enum WalletTxMessage {

    RECHARGE(new Translations("Recarga con {method}", "Recharge via {method}", "Recarga com {method}",
            "通过{method}充值", "Recharge par {method}", "Aufladung per {method}", "Ricarica con {method}",
            "Opwaardering via {method}")),
    REFUND(new Translations("Reembolso del pedido {order}", "Refund for order {order}", "Reembolso do pedido {order}",
            "订单 {order} 的退款", "Remboursement de la commande {order}", "Rückerstattung für Bestellung {order}",
            "Rimborso dell'ordine {order}", "Terugbetaling voor bestelling {order}")),
    ORDER_PAYMENT(new Translations("Pedido {order}", "Order {order}", "Pedido {order}", "订单 {order}",
            "Commande {order}", "Bestellung {order}", "Ordine {order}", "Bestelling {order}"));

    private final Translations translations;

    WalletTxMessage(Translations translations) {
        this.translations = translations;
    }

    /**
     * Las ocho traducciones de un movimiento, juntas en un solo valor: así el constructor del enum recibe un
     * parámetro en vez de ocho sueltos, donde cualquier idioma desplazado pasaba inadvertido.
     */
    public record Translations(String es, String en, String pt, String zh, String fr, String de, String it,
            String nl) {

        /** Plantilla traducida para el idioma (2 letras); si no se reconoce, devuelve el español. */
        public String of(String lang) {
            return switch (lang == null ? "es" : lang) {
                case "en" -> en;
                case "pt" -> pt;
                case "zh" -> zh;
                case "fr" -> fr;
                case "de" -> de;
                case "it" -> it;
                case "nl" -> nl;
                default -> es;
            };
        }
    }

    /** Plantilla traducida para el idioma (2 letras); si no se reconoce, devuelve el español. */
    public String of(String lang) {
        return translations.of(lang);
    }
}
