package com.nexaplatform.dropshipping.infrastructure.integration.currency;

import java.util.Map;

/**
 * Símbolo y nombre de moneda por código ISO-4217. Se usa al persistir monedas nuevas que llegan del
 * proveedor (currencylayer solo devuelve la tasa, no el símbolo). Para códigos no listados se usa el
 * propio código como símbolo/nombre — el admin puede afinarlo después. No es un conjunto de mensajes
 * traducibles, sino metadatos de presentación, por eso va como tabla de datos y no como enum.
 */
public final class CurrencySymbols {

    private CurrencySymbols() {
    }

    private static final Map<String, String[]> META = Map.ofEntries(
            Map.entry("USD", new String[] {"$", "US Dollar"}),
            Map.entry("EUR", new String[] {"€", "Euro"}),
            Map.entry("GBP", new String[] {"£", "British Pound"}),
            Map.entry("JPY", new String[] {"¥", "Japanese Yen"}),
            Map.entry("CNY", new String[] {"¥", "Chinese Yuan"}),
            Map.entry("CNH", new String[] {"¥", "Chinese Yuan (offshore)"}),
            Map.entry("HKD", new String[] {"HK$", "Hong Kong Dollar"}),
            Map.entry("AUD", new String[] {"A$", "Australian Dollar"}),
            Map.entry("CAD", new String[] {"C$", "Canadian Dollar"}),
            Map.entry("CHF", new String[] {"Fr", "Swiss Franc"}),
            Map.entry("SGD", new String[] {"S$", "Singapore Dollar"}),
            Map.entry("KRW", new String[] {"₩", "South Korean Won"}),
            Map.entry("INR", new String[] {"₹", "Indian Rupee"}),
            Map.entry("BRL", new String[] {"R$", "Brazilian Real"}),
            Map.entry("MXN", new String[] {"$", "Mexican Peso"}),
            Map.entry("ARS", new String[] {"$", "Argentine Peso"}),
            Map.entry("CLP", new String[] {"$", "Chilean Peso"}),
            Map.entry("COP", new String[] {"$", "Colombian Peso"}),
            Map.entry("PEN", new String[] {"S/", "Peruvian Sol"}),
            Map.entry("AED", new String[] {"د.إ", "UAE Dirham"}),
            Map.entry("SAR", new String[] {"﷼", "Saudi Riyal"}),
            Map.entry("TRY", new String[] {"₺", "Turkish Lira"}),
            Map.entry("RUB", new String[] {"₽", "Russian Ruble"}),
            Map.entry("ZAR", new String[] {"R", "South African Rand"}),
            Map.entry("PLN", new String[] {"zł", "Polish Zloty"}),
            Map.entry("SEK", new String[] {"kr", "Swedish Krona"}),
            Map.entry("NOK", new String[] {"kr", "Norwegian Krone"}),
            Map.entry("DKK", new String[] {"kr", "Danish Krone"}),
            Map.entry("THB", new String[] {"฿", "Thai Baht"}),
            Map.entry("IDR", new String[] {"Rp", "Indonesian Rupiah"}),
            Map.entry("MYR", new String[] {"RM", "Malaysian Ringgit"}),
            Map.entry("PHP", new String[] {"₱", "Philippine Peso"}),
            Map.entry("VND", new String[] {"₫", "Vietnamese Dong"}),
            Map.entry("NGN", new String[] {"₦", "Nigerian Naira"}),
            Map.entry("EGP", new String[] {"£", "Egyptian Pound"}),
            Map.entry("ILS", new String[] {"₪", "Israeli Shekel"}),
            Map.entry("CZK", new String[] {"Kč", "Czech Koruna"}),
            Map.entry("HUF", new String[] {"Ft", "Hungarian Forint"}),
            Map.entry("NZD", new String[] {"NZ$", "New Zealand Dollar"}),
            Map.entry("TWD", new String[] {"NT$", "Taiwan Dollar"}));

    /** Símbolo conocido para el código, o el propio código si no está mapeado. */
    public static String symbolFor(String code) {
        String[] m = META.get(code == null ? "" : code.toUpperCase());
        return m != null ? m[0] : (code == null ? "$" : code.toUpperCase());
    }

    /** Nombre conocido para el código, o el propio código si no está mapeado. */
    public static String nameFor(String code) {
        String[] m = META.get(code == null ? "" : code.toUpperCase());
        return m != null ? m[1] : (code == null ? "" : code.toUpperCase());
    }
}
