package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.cj;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * La firma con la que CJ acredita que un aviso del webhook es suyo:
 * {@code sign = Base64(HmacSHA256(secret = openId, message = cuerpo bruto))}, en la cabecera
 * {@code sign}.
 *
 * <p><b>Cuerpo BRUTO quiere decir bruto.</b> Es lo único delicado de esta clase y por eso trabaja con
 * {@code byte[]} y no con objetos: si el aviso se deserializa y se vuelve a serializar para firmarlo, el
 * resultado lleva otros espacios y a veces otro orden de campos, y entonces <b>ninguna firma cuadra
 * jamás</b>. El síntoma es de los peores: todo compila, las pruebas con cuerpos fabricados por nosotros
 * pasan, y en producción CJ recibe 401 en cada aviso hasta que desactiva el webhook por baja tasa de
 * éxito. De ahí que la prueba use un cuerpo con saltos de línea y con acentos y caracteres chinos.
 *
 * <p>El secreto es el {@code openId} de la cuenta, que CJ devuelve al autenticarse. Llega como
 * <b>número</b> en su JSON ({@code 33689} en esta cuenta) y se usa como <b>cadena</b>: firmar con otra
 * cosa —el identificador con ceros a la izquierda, o la clave de API— da una firma perfectamente válida
 * que no coincide con la suya, y el error no dice nada de eso.
 *
 * <p>Es una clase de métodos estáticos, sin estado ni dependencias de Spring, para poder probarla
 * aislada: la única forma de saber que la firma es correcta es compararla con un HmacSHA256 calculado
 * por otro camino.
 *
 * <p>La comparación va en tiempo constante ({@link MessageDigest#isEqual}) por el mismo motivo que en
 * {@code YunExpressEventCipher}: una comparación que sale antes cuando el primer carácter falla filtra,
 * medición a medición, cuál es la firma buena.
 */
public final class CjWebhookSigner {

    private static final String ALGORITMO = "HmacSHA256";

    private CjWebhookSigner() {
    }

    /** La firma que corresponde a estos bytes exactos. */
    public static String firmar(String openId, byte[] cuerpoBruto) {
        try {
            Mac mac = Mac.getInstance(ALGORITMO);
            mac.init(new SecretKeySpec(openId.getBytes(StandardCharsets.UTF_8), ALGORITMO));
            return Base64.getEncoder().encodeToString(mac.doFinal(cuerpoBruto));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("CJ: no se pudo firmar el aviso del webhook", e);
        }
    }

    /**
     * Atajo para textos que ya tenemos en memoria (pruebas, sobre todo).
     *
     * <p>Un aviso que llega por la red NO debe pasar por aquí: se firma sobre los bytes recibidos, sin
     * descodificar a texto y volver a codificar. Con UTF-8 la ida y vuelta es fiel, pero basta que
     * alguien cambie el juego de caracteres en medio para que la firma deje de cuadrar sin que nada lo
     * avise.
     */
    public static String firmar(String openId, String cuerpoBruto) {
        return firmar(openId, cuerpoBruto.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * ¿La firma recibida cuadra con el cuerpo recibido?
     *
     * <p>Cerrado por defecto: sin openId, sin cuerpo o sin firma la respuesta es que no. Quedarse sin un
     * aviso se recupera con el sondeo periódico del seguimiento; procesar uno que no viene de CJ es
     * dejar que cualquiera mueva el estado de un pedido ajeno.
     */
    public static boolean verificar(String openId, byte[] cuerpoBruto, String firmaRecibida) {
        if (openId == null || openId.isBlank() || cuerpoBruto == null
                || firmaRecibida == null || firmaRecibida.isBlank()) {
            return false;
        }
        byte[] esperada = firmar(openId, cuerpoBruto).getBytes(StandardCharsets.UTF_8);
        byte[] recibida = firmaRecibida.trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(esperada, recibida);
    }
}
