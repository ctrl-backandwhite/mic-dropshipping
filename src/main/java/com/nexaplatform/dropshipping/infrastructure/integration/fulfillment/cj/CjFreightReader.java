package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.cj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.domain.model.ShippingOption;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Qué se le pregunta a CJ para cotizar un envío, y cómo se lee lo que contesta.
 *
 * <p>Está separado de la llamada HTTP para poder probarlo con las respuestas reales de CJ sin red, que
 * es donde importa: <b>CJ responde {@code code: 200} y {@code result: true} también cuando no cotiza
 * nada</b>, así que una petición mal formada no se distingue de un destino sin cobertura. Medido contra
 * la API el 18-ago-2026, pidiendo portes de un bulto de 500 g y 20×15×5 cm de China a España:
 *
 * <ul>
 *   <li><b>El peso va en GRAMOS.</b> Con {@code weight: 500} devuelve 18 opciones. Con {@code 0.5}
 *       —el mismo peso en kilos— devuelve <b>cero</b>, y dice que todo fue bien.</li>
 *   <li><b>{@code shippingMode} y {@code platforms} NO se mandan.</b> Con los dos, cero opciones; sin
 *       ellos, las 18. Y {@code shippingMode} sin {@code platforms} hace que CJ rechace la petición con
 *       {@code 1600300}. No están en el cuerpo a propósito: <b>no los añadas</b>.</li>
 * </ul>
 *
 * <p>Es la misma familia de trampa que ya costó tiempo con YunExpress, que respondía «correcto» con
 * precios inventados cuando el peso iba sin unidad. Con CJ el síntoma es una lista vacía, que se lee
 * como «este país no tiene envío» y nadie mira más.
 */
@Slf4j
public final class CjFreightReader {

    private static final ObjectMapper JSON = new ObjectMapper();

    private CjFreightReader() {
    }

    /**
     * El cuerpo de la consulta de portes.
     *
     * <p>Se construye a mano y no con un serializador de objetos para que <b>lo que se manda esté a la
     * vista</b>: los dos campos que rompen la cotización son campos que NO están, y en un objeto con
     * anotaciones esa ausencia no se lee en ninguna parte.
     *
     * @param pesoGramos      peso del bulto EN GRAMOS (ver el javadoc de la clase)
     * @param valorTotalCents valor declarado de la mercancía en céntimos de dólar
     */
    public static String cuerpoDeConsulta(String paisOrigen, String paisDestino, String codigoPostal,
            String ciudad, String provincia, int pesoGramos, int largoCm, int anchoCm, int altoCm,
            int valorTotalCents) {
        BigDecimal valor = BigDecimal.valueOf(valorTotalCents, 2);
        return """
                {"reqDTOS":[{"srcAreaCode":"%s","destAreaCode":"%s","weight":%d,\
                "length":%d,"width":%d,"height":%d,"totalGoodsAmount":%s,\
                "productProp":["COMMON"],"zip":"%s","city":"%s","province":"%s"}]}\
                """.formatted(paisOrigen, paisDestino, pesoGramos, largoCm, anchoCm, altoCm,
                valor.toPlainString(), texto(codigoPostal), texto(ciudad), texto(provincia));
    }

    /**
     * Las formas de envío que CJ ofrece, ya en el formato del checkout.
     *
     * <p>Devuelve lista vacía ante cualquier problema en vez de lanzar: quedarse sin las opciones de un
     * transportista es una molestia, pero tumbar el checkout entero por ello es perder la venta también
     * con el otro. El aviso queda en el registro.
     */
    public static List<ShippingOption> leer(String cuerpo) {
        JsonNode raiz;
        try {
            raiz = JSON.readTree(cuerpo);
        } catch (IOException e) {
            log.warn("CJ devolvió una cotización ilegible: {}", e.getMessage());
            return List.of();
        }
        if (raiz.path("code").asInt() != 200 || !raiz.path("result").asBoolean()) {
            log.warn("CJ no cotizó ({}): {}", raiz.path("code").asInt(),
                    raiz.path("message").asText("sin mensaje"));
            return List.of();
        }
        JsonNode datos = raiz.path("data");
        if (!datos.isArray()) {
            return List.of();
        }
        List<ShippingOption> opciones = new ArrayList<>();
        for (JsonNode nodo : datos) {
            ShippingOption opcion = aOpcion(nodo);
            if (opcion != null) {
                opciones.add(opcion);
            }
        }
        return opciones;
    }

    private static ShippingOption aOpcion(JsonNode nodo) {
        // CJ marca en la propia opción por qué no sirve —peso fuera de rango, destino no cubierto—
        // dejando el resto de campos rellenos. Enseñarla sería prometer un envío que no va a salir.
        String error = nodo.path("errorEn").asText("");
        if (!error.isBlank()) {
            return null;
        }
        JsonNode precio = nodo.path("totalPostageFee");
        if (precio.isMissingNode() || precio.isNull()) {
            return null;
        }
        String nombre = nodo.path("option").path("enName").asText("");
        if (nombre.isBlank()) {
            return null;
        }
        int[] plazo = plazoDe(nodo.path("arrivalTime").asText(""));
        return new ShippingOption(nodo.path("optionId").asText(""), nombre, aCentimos(precio),
                plazo[0], plazo[1]);
    }

    /**
     * De dólares a céntimos, redondeando al más cercano.
     *
     * <p>Truncar regalaría hasta un céntimo por envío, y el precio del porte entra en la base del
     * impuesto: la diferencia se arrastra al total que se cobra.
     */
    private static int aCentimos(JsonNode precio) {
        return precio.decimalValue().movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    /**
     * El plazo llega como rango de texto ({@code "8-15"}).
     *
     * <p>Un solo número se toma como mínimo y máximo a la vez: dejar el máximo a cero haría que el
     * checkout ordenara esa opción como la más rápida de todas.
     */
    private static int[] plazoDe(String arrivalTime) {
        if (arrivalTime == null || arrivalTime.isBlank()) {
            return new int[] { 0, 0 };
        }
        String[] partes = arrivalTime.trim().split("-");
        try {
            int minimo = Integer.parseInt(partes[0].trim());
            int maximo = partes.length > 1 ? Integer.parseInt(partes[1].trim()) : minimo;
            return new int[] { minimo, maximo };
        } catch (NumberFormatException e) {
            log.warn("Plazo de CJ ilegible: {}", arrivalTime);
            return new int[] { 0, 0 };
        }
    }

    /** Evita romper el JSON si un dato de la dirección trae comillas. */
    private static String texto(String valor) {
        return valor == null ? "" : valor.replace("\"", "");
    }
}
