package com.nexaplatform.dropshipping.domain.enums;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Formato del código postal de cada país al que se vende.
 *
 * <p><b>Por qué existe.</b> La lista de zonas que el transportista no sirve se compara por número
 * —Baleares es el rango 07000-07999—, así que un código escrito como «07001A» quedaba fuera de toda
 * comparación y el pedido pasaba: se cobraba y luego no había forma de emitir la guía. La respuesta no
 * es aflojar esa comparación, sino no aceptar un código postal que el país elegido no usa. De paso
 * evita el motivo más común de entrega fallida, que es un código mal tecleado.
 *
 * <p><b>Ante un país que no está aquí, no se valida.</b> Hay países sin código postal —Hong Kong,
 * Emiratos, Panamá— y otros cuyo formato no tenemos contrastado. Inventarles una regla impediría
 * comprar a quien tiene la dirección bien, que es peor que el problema que se arregla. Por eso la
 * tabla solo lleva países cuyo formato está comprobado, y crece cuando hace falta.
 *
 * <p>En los países que SÍ están, el código es obligatorio: sin él no se puede saber si el destino es
 * servible y el transportista tampoco admite el envío.
 *
 * <p>Los patrones aceptan la forma habitual de escribir cada uno —espacio o guion opcional, minúsculas—
 * porque eso es ortografía, no un error del cliente. Lo que no aceptan es el formato de otro país.
 */
public enum PostalCodeFormat {

    /* ---- Unión Europea ---- */
    AT("\\d{4}", "1010"),
    BE("\\d{4}", "1000"),
    BG("\\d{4}", "1000"),
    CY("\\d{4}", "1010"),
    CZ("\\d{3} ?\\d{2}", "110 00"),
    DE("\\d{5}", "10115"),
    DK("\\d{4}", "1050"),
    EE("\\d{5}", "10111"),
    ES("\\d{5}", "28001"),
    FI("\\d{5}", "00100"),
    FR("\\d{5}", "75001"),
    GR("\\d{3} ?\\d{2}", "104 31"),
    HR("\\d{5}", "10000"),
    HU("\\d{4}", "1011"),
    /** Eircode: una letra, dos caracteres y cuatro alfanuméricos. */
    IE("[A-Z]\\d{2} ?[A-Z0-9]{4}", "D02 AF30"),
    IT("\\d{5}", "00184"),
    LT("(LT-)?\\d{5}", "01100"),
    LU("\\d{4}", "1009"),
    LV("(LV-)?\\d{4}", "1050"),
    MT("[A-Z]{3} ?\\d{4}", "VLT 1117"),
    NL("\\d{4} ?[A-Z]{2}", "1012 AB"),
    PL("\\d{2}[- ]?\\d{3}", "00-001"),
    PT("\\d{4}[- ]?\\d{3}", "1000-001"),
    RO("\\d{6}", "010011"),
    SE("\\d{3} ?\\d{2}", "111 29"),
    SI("(SI-)?\\d{4}", "1000"),
    SK("\\d{3} ?\\d{2}", "811 01"),

    /* ---- Resto de Europa ---- */
    CH("\\d{4}", "8001"),
    /** El código británico completo, con su parte exterior e interior. */
    GB("[A-Z]{1,2}\\d[A-Z\\d]? ?\\d[A-Z]{2}", "SW1A 1AA"),
    NO("\\d{4}", "0150"),
    RS("\\d{5}", "11000"),
    TR("\\d{5}", "34000"),
    UA("\\d{5}", "01001"),

    /* ---- América ---- */
    /** ZIP de cinco dígitos, con el sufijo de cuatro opcional. */
    US("\\d{5}([- ]?\\d{4})?", "10001"),
    CA("[A-Z]\\d[A-Z] ?\\d[A-Z]\\d", "K1A 0B1"),
    MX("\\d{5}", "01000"),
    BR("\\d{5}[- ]?\\d{3}", "01001-000"),
    AR("[A-Z]?\\d{4}[A-Z]{0,3}", "C1425"),
    CL("\\d{7}", "8320000"),
    CO("\\d{6}", "110111"),
    PE("\\d{5}", "15001"),
    UY("\\d{5}", "11000"),

    /* ---- Asia y Pacífico ---- */
    AU("\\d{4}", "2000"),
    NZ("\\d{4}", "1010"),
    JP("\\d{3}[- ]?\\d{4}", "100-0001"),
    KR("\\d{5}", "04524"),
    SG("\\d{6}", "018956"),
    MY("\\d{5}", "50450"),
    TH("\\d{5}", "10200"),
    ID("\\d{5}", "10110"),
    PH("\\d{4}", "1000"),
    IN("\\d{6}", "110001"),
    IL("\\d{5,7}", "9103401"),
    ZA("\\d{4}", "0002");

    private final Pattern pattern;
    private final String example;

    PostalCodeFormat(String regex, String example) {
        this.pattern = Pattern.compile(regex);
        this.example = example;
    }

    /** Un código postal real del país, para poder decirle al cliente qué se espera. */
    public String example() {
        return example;
    }

    /**
     * La expresión que describe el formato, para que el formulario avise antes de enviar.
     *
     * <p>Se publica el mismo texto con el que se valida aquí: mantener dos copias acaba en que una se
     * corrige y la otra no.
     */
    public String pattern() {
        return pattern.pattern();
    }

    /**
     * ¿Vale ese código postal para ese país?
     *
     * <p>Devuelve {@code true} para los países que no están en la tabla, incluidos los que no usan
     * código postal: ante la duda no se bloquea la compra.
     */
    public static boolean isValid(String countryCode, String postalCode) {
        PostalCodeFormat format = of(countryCode);
        if (format == null) {
            return true;
        }
        if (postalCode == null || postalCode.isBlank()) {
            // En un país con formato conocido el código es obligatorio: sin él no se puede comprobar si
            // el destino es servible ni despachar el envío.
            return false;
        }
        return format.pattern.matcher(normalize(postalCode)).matches();
    }

    /** El ejemplo del país, o cadena vacía si su formato no está en la tabla. */
    public static String exampleFor(String countryCode) {
        PostalCodeFormat format = of(countryCode);
        return format == null ? "" : format.example;
    }

    /** El formato del país, o {@code null} si no lo conocemos. */
    public static PostalCodeFormat of(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) {
            return null;
        }
        try {
            return valueOf(countryCode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Mayúsculas y un solo espacio: escribir «1234ab» o «1234 AB» es la misma dirección. */
    private static String normalize(String postalCode) {
        return postalCode.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }
}
