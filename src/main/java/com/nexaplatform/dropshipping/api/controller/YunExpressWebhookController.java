package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.FulfillmentService;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.YunExpressEventCipher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook ENTRANTE de YunExpress (事件管理): recibe los eventos de trazabilidad suscritos con
 * {@code /v1/track-service/subscribe-by-order} y los aplica al timeline del pedido.
 *
 * <p>El cuerpo llega firmado y cifrado con el <i>Encrypt Key</i> del console; la verificación y el
 * descifrado viven en {@link YunExpressEventCipher}. Es <b>fail-closed</b>: si la firma no cuadra el push
 * se rechaza con 401 sin tocar nada. Un error al procesar devuelve 500 a propósito, para que YunExpress
 * reintente y no se pierda el evento.
 *
 * <p><b>URL a registrar</b> en 开发配置 → 事件管理 (debe ser pública; localhost no es alcanzable desde
 * YunExpress, hace falta un túnel):
 * <ul>
 *   <li>Desarrollo: {@code https://back-dropshipping-des.up.railway.app/api/webhooks/yunexpress}</li>
 * </ul>
 *
 * <p>La ruta {@code /api/webhooks/**} ya es pública en la configuración de seguridad.
 */
@Slf4j
@RestController
@RequestMapping("/api/webhooks/yunexpress")
@RequiredArgsConstructor
public class YunExpressWebhookController {

    private final YunExpressEventCipher cipher;
    private final FulfillmentService fulfillmentService;

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> receive(
            @RequestHeader(value = "X-Openapi-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Openapi-Signature", required = false) String signature,
            @RequestBody String rawBody) {
        // ORDEN CRÍTICO — NO MOVER: primero se VERIFICA la firma sobre el cuerpo crudo (que incluye el
        // criptograma del campo `encrypt`) y solo después se descifra, dentro de applyYunExpressPush.
        // Es la construcción encrypt-then-MAC: es lo que aporta la integridad que AES-CBC no trae por sí
        // solo y lo que convierte el aviso CIPHER_INTEGRITY de Find Security Bugs en un falso positivo.
        // Descifrar antes de verificar expondría el descifrado a criptogramas de cualquiera.
        // Fijado por YunExpressWebhookIntegrityOrderTest.
        if (!cipher.verify(timestamp, rawBody, signature)) {
            log.warn("YunExpress webhook: firma inválida o clave de cifrado sin configurar — rechazado");
            return ResponseEntity.status(401).body("{\"success\":false}");
        }
        try {
            fulfillmentService.applyYunExpressPush(rawBody);
        } catch (RuntimeException e) {
            log.error("YunExpress webhook: error al procesar el push", e);
            return ResponseEntity.status(500).body("{\"success\":false}");
        }
        return ResponseEntity.ok("{\"success\":true}");
    }
}
