package com.nexaplatform.dropshipping.domain.enums;

import java.util.Arrays;
import java.util.List;

/**
 * Las cuatro figuras que el art. 4.2 del Reglamento (UE) 2019/1020 admite como operador económico
 * establecido en la Unión. La elección no es cosmética: determina de qué responde quien la asume.
 *
 * <p>En un negocio que vende en la UE mercancía fabricada fuera y sin fabricante establecido en la Unión, la
 * figura que corresponde es {@link #IMPORTER}.
 */
public enum EuOperatorRole {

    /** Art. 4.2.a — fabricante establecido en la Unión. */
    MANUFACTURER("Fabricante", "Manufacturer", "Fabricante", "制造商", "Fabricant", "Hersteller",
            "Fabbricante", "Fabrikant"),

    /** Art. 4.2.b — importador, cuando el fabricante no está establecido en la Unión. */
    IMPORTER("Importador", "Importer", "Importador", "进口商", "Importateur", "Importeur",
            "Importatore", "Importeur"),

    /** Art. 4.2.c — representante autorizado con mandato escrito del fabricante. */
    AUTHORISED_REPRESENTATIVE("Representante autorizado", "Authorised representative",
            "Representante autorizado", "授权代表", "Mandataire", "Bevollmächtigter",
            "Rappresentante autorizzato", "Gemachtigde"),

    /** Art. 4.2.d — prestador de servicios logísticos, solo si ninguna de las anteriores está en la Unión. */
    FULFILMENT_SERVICE_PROVIDER("Prestador de servicios logísticos", "Fulfilment service provider",
            "Prestador de serviços logísticos", "物流服务提供商", "Prestataire de services d'exécution",
            "Fulfilment-Dienstleister", "Fornitore di servizi di logistica", "Fulfilmentdienstverlener");

    private static final List<String> LANGUAGES = List.of("es", "en", "pt", "zh", "fr", "de", "it", "nl");

    private final List<String> labels;

    EuOperatorRole(String... labels) {
        this.labels = List.of(labels);
    }

    /** Etiqueta en el idioma pedido; cae a español si el idioma no está soportado. */
    public String label(String lang) {
        int i = LANGUAGES.indexOf(lang == null ? "" : lang.toLowerCase());
        return labels.get(i < 0 ? 0 : i);
    }

    /** Devuelve la figura por su nombre, o {@link #IMPORTER} si el valor almacenado no es reconocible. */
    public static EuOperatorRole from(String value) {
        return Arrays.stream(values())
                .filter(r -> r.name().equalsIgnoreCase(value == null ? "" : value.trim()))
                .findFirst()
                .orElse(IMPORTER);
    }
}
