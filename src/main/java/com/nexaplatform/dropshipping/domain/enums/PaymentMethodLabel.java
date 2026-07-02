package com.nexaplatform.dropshipping.domain.enums;

import java.util.Locale;

/**
 * Nombre traducido del método de pago para la factura y los emails transaccionales. Se muestra en el
 * idioma del usuario (los 8 soportados) en vez del código en crudo ("CARD"). PayPal y USDT son marcas
 * o símbolos: NO se traducen, son iguales en todos los idiomas.
 */
public enum PaymentMethodLabel {

    CARD("Tarjeta", "Card", "Cartão", "银行卡", "Carte", "Karte", "Carta", "Kaart"),
    PAYPAL("PayPal", "PayPal", "PayPal", "PayPal", "PayPal", "PayPal", "PayPal", "PayPal"),
    WALLET("Billetera", "Wallet", "Carteira", "钱包", "Portefeuille", "Geldbörse", "Portafoglio", "Portemonnee"),
    USDT("USDT", "USDT", "USDT", "USDT", "USDT", "USDT", "USDT", "USDT");

    private final String es;
    private final String en;
    private final String pt;
    private final String zh;
    private final String fr;
    private final String de;
    private final String it;
    private final String nl;

    PaymentMethodLabel(String es, String en, String pt, String zh, String fr, String de, String it, String nl) {
        this.es = es;
        this.en = en;
        this.pt = pt;
        this.zh = zh;
        this.fr = fr;
        this.de = de;
        this.it = it;
        this.nl = nl;
    }

    private String forLang(String lang) {
        switch (lang) {
            case "en":
                return en;
            case "pt":
                return pt;
            case "zh":
                return zh;
            case "fr":
                return fr;
            case "de":
                return de;
            case "it":
                return it;
            case "nl":
                return nl;
            default:
                return es;
        }
    }

    /**
     * Traduce el código del método de pago (CARD/PAYPAL/WALLET/USDT) al idioma del locale indicado
     * ("es", "en-US", "pt_BR"...). Si el método no se reconoce, devuelve el valor original tal cual
     * para no perder información.
     */
    public static String localize(String method, String locale) {
        if (method == null || method.isBlank()) {
            return method;
        }
        String lang = locale == null ? "es" : locale.trim().toLowerCase(Locale.ROOT).split("[-_]")[0];
        try {
            return valueOf(method.trim().toUpperCase(Locale.ROOT)).forLang(lang);
        } catch (IllegalArgumentException e) {
            return method;
        }
    }
}
