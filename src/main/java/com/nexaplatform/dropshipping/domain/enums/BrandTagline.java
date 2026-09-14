package com.nexaplatform.dropshipping.domain.enums;

import java.util.Locale;

/**
 * El descriptor que acompaña al nombre en la cabecera de los correos: «NX036 · Moda y complementos».
 *
 * <p><b>Por qué existe.</b> Ahí ponía «Dropshipping», que describe cómo se cumple el pedido —sin
 * almacén propio, envía el proveedor— y no lo que se vende. Es vocabulario de operador: a quien
 * acaba de comprar una camiseta no le dice qué tienda le escribe, y en el mercado de consumo la
 * palabra arrastra fama de producto revendido con recargo. Para un socio de integración, en cambio,
 * «dropshipping» SÍ es la propuesta —somos su proveedor—, así que esos correos la conservan pasando
 * su propio {@code tagline}.
 *
 * <p>El texto sale de lo que de verdad hay en el catálogo: el 92 % son moda, calzado, bolsos y ropa
 * interior. No promete secciones que no existen.
 */
public enum BrandTagline {

    /** «Moda y complementos» en los ocho idiomas de la plataforma. Es lo que ve quien compra. */
    DEFAULT("Moda y complementos", "Fashion and accessories", "Moda e acessórios", "时尚与配饰",
            "Mode et accessoires", "Mode und Accessoires", "Moda e accessori", "Mode en accessoires"),

    /**
     * Lo que ve un socio de integración. Para él la palabra no es un demérito sino el servicio que
     * contrata: vende en su tienda y nosotros enviamos. En chino se usa el término del sector,
     * «一件代发», porque «dropshipping» no se lee allí.
     */
    PARTNER("Dropshipping", "Dropshipping", "Dropshipping", "一件代发",
            "Dropshipping", "Dropshipping", "Dropshipping", "Dropshipping");

    private final String es;
    private final String en;
    private final String pt;
    private final String zh;
    private final String fr;
    private final String de;
    private final String it;
    private final String nl;

    BrandTagline(String es, String en, String pt, String zh, String fr, String de, String it, String nl) {
        this.es = es;
        this.en = en;
        this.pt = pt;
        this.zh = zh;
        this.fr = fr;
        this.de = de;
        this.it = it;
        this.nl = nl;
    }

    /** El descriptor de consumo en ese idioma; en español cuando el idioma falta o no se sirve. */
    public static String of(String lang) {
        return DEFAULT.texto(lang);
    }

    /**
     * El descriptor según a quién se escribe. Al socio de integración se le sigue hablando de
     * dropshipping —es literalmente lo que ha contratado—; a todos los demás, de lo que se vende.
     */
    public static String of(UserRole role, String lang) {
        return (role == UserRole.PARTNER ? PARTNER : DEFAULT).texto(lang);
    }

    private String texto(String lang) {
        return switch (lang == null ? "" : lang.trim().toLowerCase(Locale.ROOT)) {
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
