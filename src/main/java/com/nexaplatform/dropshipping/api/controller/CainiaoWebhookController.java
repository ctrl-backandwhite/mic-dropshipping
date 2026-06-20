package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.FulfillmentService;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.CainiaoLinkClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook ENTRANTE de Cainiao Link (push de la fase 2). Una sola URL para todos los {@code msg_type} de
 * push ({@code TRACEPUSH}, {@code CAINIAO_GLOBAL_FULFILL_STATUS_SYNC}, …): el gateway envía
 * {@code application/x-www-form-urlencoded} con {@code msg_type} + {@code logistics_interface} +
 * {@code data_digest}. Se verifica la firma (fail-closed) y se delega el parseo/aplicación a
 * {@link FulfillmentService#applyPush}.
 *
 * <p><b>Request address a registrar en Cainiao</b> (campo de la Register API):
 * <ul>
 *   <li>Desarrollo: {@code https://back-dropshipping-des.up.railway.app/api/webhooks/cainiao}</li>
 *   <li>Local (necesita túnel público, p.ej. ngrok, porque Cainiao no alcanza localhost):
 *       {@code http://localhost:18082/api/webhooks/cainiao}</li>
 * </ul>
 *
 * <p>Ruta pública: {@code /api/webhooks/**} ya está en el permitAll de BffSecurityConfig.
 */
@Slf4j
@RestController
@RequestMapping("/api/webhooks/cainiao")
@RequiredArgsConstructor
public class CainiaoWebhookController {

    private final CainiaoLinkClient linkClient;
    private final FulfillmentService fulfillmentService;

    @PostMapping(consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> receive(
            @RequestParam(value = "msg_type", required = false) String msgType,
            @RequestParam(value = "logistics_interface", required = false) String logisticsInterface,
            @RequestParam(value = "data_digest", required = false) String dataDigest) {
        // Fail-closed: sin payload o con firma inválida → se rechaza, no se procesa.
        if (logisticsInterface == null || !linkClient.verify(logisticsInterface, dataDigest)) {
            log.warn("Cainiao webhook {}: firma inválida o payload ausente — rechazado", msgType);
            return ResponseEntity.status(401).body("{\"success\":\"false\",\"errorCode\":\"S01\"}");
        }
        try {
            fulfillmentService.applyPush(msgType, logisticsInterface);
        } catch (RuntimeException e) {
            // Devolvemos 500 para que Cainiao reintente el push (no perdemos el evento).
            log.error("Cainiao webhook {}: error al procesar el push", msgType, e);
            return ResponseEntity.status(500).body("{\"success\":\"false\",\"errorCode\":\"P01\"}");
        }
        // TODO(real): confirmar el formato de ACK exacto que espera cada API en su doc; {"success":"true"} es el habitual del gateway Link.
        return ResponseEntity.ok("{\"success\":\"true\"}");
    }
}
