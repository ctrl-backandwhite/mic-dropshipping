package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PaymentEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PaymentRepository;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * Puente de vuelta del pago hacia la aplicación móvil.
 *
 * <p>La pasarela solo admite direcciones http(s) como página de retorno, y el teléfono solo devuelve
 * el foco a la app por su esquema propio (<code>nx036://</code>). Sin este puente, quien recargaba
 * desde el móvil pagaba en la pasarela y se quedaba mirando una página que el teléfono no sabe abrir:
 * el cobro se hacía y la app nunca llegaba a confirmarlo.
 *
 * <p>No decide nada sobre el dinero: solo redirige. Quien cierra el cobro es el backend cuando la app
 * llama a confirmar, igual que en la web.
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentAppReturnController {

    private final PaymentRepository payments;

    /** Prefijo del enlace profundo del pago. Debe coincidir con el `scheme` de la app. */
    @Value("${nexadrop.mobile.payment-return-url:nx036://pago}")
    private String appPaymentReturnUrl;

    @Operation(summary = "Vuelta del Checkout hospedado a la aplicación móvil (redirección al esquema propio)")
    @GetMapping("/app-return")
    public ResponseEntity<Void> appReturn(@RequestParam UUID paymentId,
            @RequestParam(required = false) String status,
            @RequestParam(name = "session_id", required = false) String sessionId) {
        // Cualquier estado que no sea un «ok» explícito se trata como cancelado: anunciar un cobro que
        // no consta sería peor que pedir que se reintente.
        boolean aprobado = "ok".equalsIgnoreCase(status);
        StringBuilder destino = new StringBuilder(appPaymentReturnUrl)
                .append(aprobado ? "/retorno" : "/cancelado")
                .append("?paymentId=").append(paymentId);
        // Qué se estaba pagando: la app tiene que cerrar el cobro por un sitio distinto según sea la
        // recarga del monedero o un pedido, y cuando vuelve del navegador ya no le queda contexto.
        UUID orderId = payments.findById(paymentId).map(PaymentEntity::getOrderId).orElse(null);
        destino.append("&kind=").append(orderId == null ? "recharge" : "order");
        if (orderId != null) {
            destino.append("&orderId=").append(orderId);
        }
        if (aprobado && sessionId != null && !sessionId.isBlank()) {
            destino.append("&sessionId=").append(sessionId);
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, destino.toString()).build();
    }
}
