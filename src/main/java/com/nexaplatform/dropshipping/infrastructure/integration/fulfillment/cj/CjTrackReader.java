package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.cj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Cómo se lee el seguimiento que devuelve CJ ({@code GET /api2.0/v1/logistic/trackInfo?trackNumber=…}).
 *
 * <p>Está separado de la llamada HTTP —igual que {@link CjFreightReader}— para poder probarlo con las
 * respuestas literales de CJ sin red. La consulta admite <b>varios números separados por coma</b>, así
 * que un pedido repartido en bultos se sondea de una vez y aquí vuelve una entrada por bulto: cada una
 * conserva su número, porque quien agrega necesita saber qué bulto va más atrasado.
 *
 * <p>Dos decisiones que se toman aquí y que se notan en el pedido del cliente:
 *
 * <ul>
 *   <li><b>Un {@code trackingStatus} que no está en {@link CjTrackStatus} no produce hito.</b> Se anota
 *       con el texto exacto —para poder añadirlo a la tabla— y se ignora, de modo que el pedido se
 *       queda donde estaba. Adivinar aquí es cerrar pedidos que aún viajan.</li>
 *   <li><b>{@code deliveryTime} llega sin zona horaria</b> ({@code "2021-06-17 07:04:04"}). Ver
 *       {@link #momentoDe(JsonNode)}.</li>
 * </ul>
 *
 * <p>Como en la cotización, ante cualquier problema se devuelve lista vacía en vez de lanzar: quedarse
 * sin la actualización de un sondeo se arregla en el siguiente, pero romper el sondeo programado deja
 * sin avanzar también a los pedidos del otro transportista.
 */
@Slf4j
public final class CjTrackReader {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final DateTimeFormatter FORMATO_FECHA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Zona con la que se interpreta {@code deliveryTime}: CJ no manda desplazamiento y su panel trabaja
     * en hora de Pekín. Leerlo como UTC colocaría cada hito <b>8 horas por delante</b> y el cliente
     * vería «entregado» con fecha de mañana.
     */
    private static final ZoneId ZONA_DE_CJ = ZoneId.of("Asia/Shanghai");

    private CjTrackReader() {
    }

    /**
     * El seguimiento de UN bulto tal como lo cuenta CJ.
     *
     * @param numeroSeguimiento        la guía de CJ, la misma con la que se creó el envío
     * @param nombreLogistico          el canal («CJPacket Sensitive»), útil solo para el registro
     * @param transportistaUltimaMilla quién reparte en destino
     * @param numeroUltimaMilla        el número del transportista local, que es con el que el cliente
     *                                 puede consultar en la web de su país
     */
    public record Seguimiento(String numeroSeguimiento, String nombreLogistico, CjTrackStatus estado,
            String transportistaUltimaMilla, String numeroUltimaMilla, Instant momento) {
    }

    /**
     * El seguimiento de cada bulto que CJ reconoció, en el orden en que lo devuelve.
     *
     * <p>Los bultos cuyo estado no se entiende <b>no salen en la lista</b>: es lo que hace que un estado
     * nuevo de CJ no mueva el pedido.
     */
    public static List<Seguimiento> leer(String cuerpo) {
        if (cuerpo == null || cuerpo.isBlank()) {
            return List.of();
        }
        JsonNode raiz;
        try {
            raiz = JSON.readTree(cuerpo);
        } catch (IOException e) {
            log.warn("CJ devolvió un seguimiento ilegible: {}", e.getMessage());
            return List.of();
        }
        if (raiz.path("code").asInt() != 200 || !raiz.path("result").asBoolean()) {
            log.warn("CJ no devolvió seguimiento ({}): {}", raiz.path("code").asInt(),
                    raiz.path("message").asText("sin mensaje"));
            return List.of();
        }
        JsonNode datos = raiz.path("data");
        if (!datos.isArray()) {
            return List.of();
        }
        List<Seguimiento> seguimientos = new ArrayList<>();
        for (JsonNode nodo : datos) {
            Seguimiento seguimiento = aSeguimiento(nodo);
            if (seguimiento != null) {
                seguimientos.add(seguimiento);
            }
        }
        return seguimientos;
    }

    private static Seguimiento aSeguimiento(JsonNode nodo) {
        String numero = nodo.path("trackingNumber").asText("").trim();
        if (numero.isEmpty()) {
            // Sin número no se sabe a qué bulto pertenece el hito, y en una consulta en lote asignarlo
            // al pedido equivocado es peor que perderlo.
            log.warn("CJ devolvió un hito de seguimiento sin número; se descarta");
            return null;
        }
        String texto = nodo.path("trackingStatus").asText("").trim();
        CjTrackStatus estado = CjTrackStatus.desde(texto);
        if (estado == null) {
            // Un número que CJ no reconoce vuelve sin estado; uno que sí reconoce puede traer un estado
            // que aún no está en la tabla. Los dos se anotan con el texto literal para poder añadirlo.
            log.warn("CJ: estado de seguimiento no reconocido '{}' en la guía {} — el pedido no avanza",
                    texto, numero);
            return null;
        }
        return new Seguimiento(numero, nodo.path("logisticName").asText(""), estado,
                nodo.path("lastMileCarrier").asText(""), nodo.path("lastTrackNumber").asText(""),
                momentoDe(nodo));
    }

    /**
     * Cuándo ocurrió el hito, a partir de {@code deliveryTime} ({@code "yyyy-MM-dd HH:mm:ss"}).
     *
     * <p>CJ manda la fecha <b>sin zona</b>, así que hay que elegir una: se lee como hora de Pekín (ver
     * {@link #ZONA_DE_CJ}). Al ser la misma para todos los hitos, la elección no altera el <i>orden</i>
     * de la línea temporal ni la deduplicación —que va por estado y descripción, no por fecha—, solo la
     * hora que se enseña.
     *
     * <p>Si falta o no se entiende se usa la de ahora, nunca {@code null}: el timeline ordena los pasos
     * con {@code Comparator.comparing(TrackingStep::occurredAt)} y un nulo ahí revienta el sondeo
     * entero, que es justo lo que este lector evita.
     */
    private static Instant momentoDe(JsonNode nodo) {
        String valor = nodo.path("deliveryTime").asText("").trim();
        if (valor.isEmpty()) {
            return Instant.now();
        }
        try {
            return LocalDateTime.parse(valor, FORMATO_FECHA).atZone(ZONA_DE_CJ).toInstant();
        } catch (DateTimeParseException e) {
            log.warn("CJ: fecha de seguimiento no parseable '{}' — se usa la hora actual", valor);
            return Instant.now();
        }
    }
}
