package com.nexaplatform.dropshipping.domain.enums;

import java.util.List;
import java.util.Locale;

/**
 * Nombre traducido del método de pago para la factura y los emails transaccionales. Se muestra en el
 * idioma del usuario (los 8 soportados) en vez del código en crudo ("CARD"). PayPal y USDT son marcas
 * o símbolos: NO se traducen, son iguales en todos los idiomas.
 */
public enum PaymentMethodLabel {

    CARD("Tarjeta", "Card", "Cartão", "银行卡", "Carte", "Karte", "Carta", "Kaart"),
    PAYPAL("PayPal"),
    WALLET("Billetera", "Wallet", "Carteira", "钱包", "Portefeuille", "Geldbörse", "Portafoglio", "Portemonnee"),
    USDT("USDT");

    /**
     * Los 8 idiomas soportados EN EL ORDEN en que se declaran los textos de cada constante. El primero
     * (español) es además el que se usa cuando el idioma pedido no está en la lista.
     */
    private static final List<String> LANGUAGES = List.of("es", "en", "pt", "zh", "fr", "de", "it", "nl");

    /** Un texto por idioma, alineado posicionalmente con {@link #LANGUAGES}. */
    private final List<String> byLanguage;

    /**
     * Un texto por idioma en el orden de {@link #LANGUAGES}, o UNO SOLO cuando es una marca o símbolo
     * (PayPal, USDT) que se escribe igual en todos: así queda escrito en el código que NO se traduce a
     * propósito, en vez de repetir el literal ocho veces.
     *
     * <p>El caso de un único texto se resuelve al leer y no aquí porque el constructor de un enum se
     * ejecuta ANTES de que existan sus campos estáticos: {@code LANGUAGES} todavía no está disponible.
     */
    PaymentMethodLabel(String... translations) {
        this.byLanguage = List.of(translations);
    }

    private String forLang(String lang) {
        if (byLanguage.size() == 1) {
            return byLanguage.get(0);
        }
        int i = LANGUAGES.indexOf(lang);
        return byLanguage.get(i < 0 ? 0 : i);
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
