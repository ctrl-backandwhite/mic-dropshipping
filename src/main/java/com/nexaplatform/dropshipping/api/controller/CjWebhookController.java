package com.nexaplatform.dropshipping.api.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.cj.CjAuthService;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.cj.CjWebhookSigner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Webhook ENTRANTE de CJ Dropshipping: recibe los avisos suscritos con {@code /api2.0/v1/webhook/set}
 * (pedido, logística, producto, stock, recargos e inventario privado).
 *
 * <p>El contrato de CJ, verificado en su documentación el 18-ago-2026, marca las tres reglas de este
 * controlador:
 *
 * <ol>
 *   <li><b>Firma.</b> {@code sign = Base64(HmacSHA256(secret = openId, cuerpo bruto))} en la cabecera
 *       {@code sign}. Por eso el cuerpo entra como {@code byte[]}: ver {@link CjWebhookSigner}.</li>
 *   <li><b>Rapidez.</b> Hay que devolver 200 en <b>menos de tres segundos</b>. CJ reintenta tres veces
 *       y <b>cierra el tema del webhook</b> si la tasa de éxito de dos horas completas seguidas baja del
 *       80 %. Aquí dentro solo se comprueba la firma, se mira el {@code messageId} y se entrega el aviso;
 *       el trabajo de verdad —tocar el pedido, mover el seguimiento— lo hace quien escuche el evento,
 *       y <b>ese oyente tiene que ser {@code @Async}</b>: un oyente síncrono gastaría el presupuesto de
 *       tres segundos dentro de esta llamada y volveríamos al punto de partida.</li>
 *   <li><b>Duplicados.</b> El {@code messageId} <b>no cambia entre reintentos</b>, así que sirve de
 *       llave para no procesar dos veces. Un aviso repetido se contesta con 200 igualmente: con un
 *       error, CJ lo reintentaría sin fin y además nos contaría el fallo para la tasa del 80 %.</li>
 * </ol>
 *
 * <h2>Alta de la URL en CJ: qué se sabe y qué hay que comprobar</h2>
 *
 * <p>Con YunExpress el alta del webhook falló durante días porque su documentación decía que la
 * comprobación de la dirección iba <b>sin firmar</b>; se capturó la petición real y resultó ser justo lo
 * contrario (firmada y cifrada, con un {@code ack} dentro que había que devolver). La lección no es
 * «CJ hará lo mismo», es <b>no fiarse de lo que diga la documentación sobre el alta</b>.
 *
 * <p>Lo comprobado en la documentación de CJ (18-ago-2026): al registrar la URL con {@code /webhook/set}
 * solo se describe una validación <b>de forma</b> —tiene que ser HTTPS pública, y rechaza
 * {@code localhost} o {@code 127.0.0.1} con el error {@code 1607001}—, y <b>no se menciona ninguna
 * llamada de prueba, saludo, reto ni eco de un token</b> contra la URL. Tampoco se pide devolver ningún
 * cuerpo concreto: para CJ el éxito es el código 200 a secas.
 *
 * <p>Ahora bien, ausencia de mención no es prueba de ausencia, y este endpoint rechaza con 401 todo lo
 * que llegue sin firma válida. Si el alta fallase, <b>lo primero que hay que mirar es el registro de
 * accesos</b>: si aparece un POST (o un GET) a esta ruta sin cabecera {@code sign} justo al dar de alta,
 * es exactamente el caso de YunExpress. La salida entonces <b>no es abrir el endpoint</b>, sino
 * reconocer ese saludo concreto y contestarlo, como se hizo allí con {@code ackOf}: la comprobación de
 * firma de los avisos reales no se toca. Mientras no se vea esa petición, no se implementa nada: una
 * puerta sin firma «por si acaso» es una puerta sin firma.
 *
 * <p>La ruta {@code /api/webhooks/**} ya es pública en la configuración de seguridad.
 */
@Slf4j
@RestController
@RequestMapping("/api/webhooks/cj")
@RequiredArgsConstructor
public class CjWebhookController {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** CJ solo mira el código de estado, pero un cuerpo JSON deja el rastro legible en sus reintentos. */
    private static final String ACEPTADO = "{\"success\":true}";
    private static final String RECHAZADO = "{\"success\":false}";

    /**
     * Cuántos {@code messageId} se recuerdan. Los reintentos de CJ llegan en minutos y como mucho son
     * tres, así que con unos miles hay de sobra para cubrir la ventana incluso en un día de mucho
     * movimiento, y el coste en memoria es despreciable.
     */
    private static final int AVISOS_RECORDADOS = 5000;

    private final CjAuthService auth;
    private final ApplicationEventPublisher eventos;

    /**
     * Los avisos ya procesados, para no repetirlos.
     *
     * <p>Es memoria del proceso, con lo que eso implica y conviene tener escrito: <b>no sobrevive a un
     * reinicio ni se comparte entre réplicas</b>. Es la primera barrera, la barata; la que de verdad
     * cierra el paso es la de más abajo —el seguimiento ya descarta los hitos repetidos en
     * {@code FulfillmentService.collectNewSteps}—, y el día que el aviso se guarde en tabla, la llave
     * única por {@code messageId} sustituye a esto.
     */
    private final Map<String, Boolean> yaProcesados = Collections.synchronizedMap(new MemoriaDeAvisos());

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> recibir(
            @RequestHeader(value = "sign", required = false) String firma,
            @RequestBody byte[] cuerpoBruto) {
        // El openId sale del token ya guardado en la base: comprobar una firma no puede costar una
        // llamada a CJ, porque la respuesta tiene que salir en menos de tres segundos.
        String openId = auth.openId();
        if (openId == null || openId.isBlank()) {
            log.error("Webhook de CJ: no hay openId guardado, así que no se puede comprobar la firma. "
                    + "Los avisos se rechazan hasta que la cuenta se autentique al menos una vez.");
            return ResponseEntity.status(401).body(RECHAZADO);
        }
        if (!CjWebhookSigner.verificar(openId, cuerpoBruto, firma)) {
            log.warn("Webhook de CJ: firma ausente o que no cuadra — aviso rechazado");
            return ResponseEntity.status(401).body(RECHAZADO);
        }

        String cuerpo = new String(cuerpoBruto, StandardCharsets.UTF_8);
        JsonNode aviso;
        try {
            aviso = JSON.readTree(cuerpo);
        } catch (JsonProcessingException e) {
            // Viene firmado, o sea que es de CJ: si no se puede leer, el problema no lo arregla un
            // reintento. Devolver error aquí solo serviría para bajar la tasa de éxito hacia el 80 %.
            log.warn("Webhook de CJ: cuerpo firmado pero ilegible ({})", e.getOriginalMessage());
            return ResponseEntity.ok(ACEPTADO);
        }

        Optional<TipoDeAviso> tipo = TipoDeAviso.de(aviso.path("type").asText(""));
        if (tipo.isEmpty()) {
            // Que CJ estrene un tipo de aviso no es un fallo nuestro, y tratarlo como tal haría que
            // nos cerrasen el webhook por el que sí llegan las entregas.
            log.info("Webhook de CJ: tipo de aviso no contemplado ({}) — aceptado sin procesar",
                    aviso.path("type").asText(""));
            return ResponseEntity.ok(ACEPTADO);
        }

        String messageId = aviso.path("messageId").asText("");
        if (!messageId.isBlank() && yaProcesados.putIfAbsent(messageId, Boolean.TRUE) != null) {
            log.debug("Webhook de CJ: aviso {} repetido — ya se procesó", messageId);
            return ResponseEntity.ok(ACEPTADO);
        }

        try {
            eventos.publishEvent(new AvisoDeCj(messageId, tipo.get(),
                    aviso.path("messageType").asText(""), cuerpo));
        } catch (RuntimeException e) {
            // No llegó a procesarse, así que tampoco puede contar como procesado: se olvida para que el
            // reintento de CJ —que trae el mismo messageId— entre como si fuera la primera vez.
            yaProcesados.remove(messageId);
            log.error("Webhook de CJ: no se pudo encolar el aviso {}", messageId, e);
            return ResponseEntity.status(500).body(RECHAZADO);
        }
        return ResponseEntity.ok(ACEPTADO);
    }

    /**
     * Los {@code messageId} recientes, con olvido del más antiguo cuando se llena.
     *
     * <p>Sin ese olvido la memoria crecería sin techo con cada aviso recibido, que en una tienda con
     * movimiento es una fuga lenta que solo se ve cuando el proceso muere.
     */
    private static final class MemoriaDeAvisos extends LinkedHashMap<String, Boolean> {

        private static final long serialVersionUID = 1L;

        private MemoriaDeAvisos() {
            super(16, 0.75f, true);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > AVISOS_RECORDADOS;
        }
    }

    /**
     * Un aviso de CJ ya comprobado, listo para procesarlo <b>fuera de esta llamada</b>.
     *
     * <p>Lleva el cuerpo tal y como llegó y no un objeto ya interpretado: cada tipo de aviso trae unos
     * {@code params} distintos, y quien los entienda es quien debe leerlos. Aquí solo se sabe de firmas
     * y de duplicados.
     *
     * @param messageId llave del aviso en CJ; cadena vacía si no venía (raro, pero no se descarta por eso)
     * @param accion    el {@code messageType} de CJ: {@code INSERT}, {@code UPDATE}, {@code CANCEL} o,
     *                  en los recargos, {@code PAID}
     */
    public record AvisoDeCj(String messageId, TipoDeAviso tipo, String accion, String cuerpo) {
    }

    /**
     * Los seis temas que CJ puede empujar.
     *
     * <p>CJ los escribe de dos maneras según el sitio: {@code privateOrder} al suscribirse y
     * {@code PRIVATE_ORDER} dentro del aviso. Se comparan sin mayúsculas ni guiones bajos para que las
     * dos grafías sean el mismo tema; con una comparación literal, los avisos de inventario privado se
     * habrían descartado en silencio como «tipo desconocido».
     */
    public enum TipoDeAviso {
        PRODUCT, STOCK, ORDER, LOGISTICS, MAKEUP, PRIVATE_ORDER;

        public static Optional<TipoDeAviso> de(String texto) {
            if (texto == null || texto.isBlank()) {
                return Optional.empty();
            }
            String normalizado = normalizar(texto);
            for (TipoDeAviso candidato : values()) {
                if (normalizar(candidato.name()).equals(normalizado)) {
                    return Optional.of(candidato);
                }
            }
            return Optional.empty();
        }

        private static String normalizar(String texto) {
            return texto.trim().toUpperCase(Locale.ROOT).replace("_", "");
        }
    }
}
