package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.FulfillmentService;
import com.nexaplatform.dropshipping.api.mapper.TrackingViewMapper;
import com.nexaplatform.dropshipping.application.service.FulfillmentService.TrackingView;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentSyncScheduler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Seguimiento del envío: el timeline de eventos que ve el cliente desde el pedido hasta la entrega, y la
 * vista + sincronización forzada para el admin. La construcción de la respuesta vive en
 * {@link FulfillmentService} (transaccional, sin tocar los items del pedido).
 */
@Tag(name = "Order Tracking", description = "Timeline de seguimiento del envío")
@RestController
@RequiredArgsConstructor
@Slf4j
public class TrackingController {

    private final FulfillmentService fulfillmentService;
    private final FulfillmentSyncScheduler syncScheduler;
    private final TrackingViewMapper trackingViewMapper;

    @Operation(summary = "Timeline de seguimiento del pedido del usuario autenticado")
    @GetMapping("/api/me/orders/{id}/tracking")
    public ResponseEntity<TrackingView> myTracking(Authentication auth, @PathVariable UUID id) {
        return ResponseEntity.ok(fulfillmentService.myTrackingView(UUID.fromString(auth.getName()), id));
    }

    @Operation(summary = "Timeline de seguimiento de cualquier pedido (admin)")
    @GetMapping("/api/admin/orders/{id}/tracking")
    public ResponseEntity<TrackingView> adminTracking(@PathVariable UUID id) {
        return ResponseEntity.ok(fulfillmentService.adminTrackingView(id));
    }

    @Operation(summary = "Forzar la sincronización del seguimiento con el transportista (admin)")
    @PostMapping("/api/admin/orders/{id}/sync-tracking")
    public ResponseEntity<TrackingView> sync(@PathVariable UUID id) {
        syncScheduler.syncOrderById(id);
        return ResponseEntity.ok(fulfillmentService.adminTrackingView(id));
    }

    /** Envío abandonado a la espera de intervención: qué pedido, cuántos intentos y por qué falló. */
    public record FailedFulfillmentView(UUID orderId, String orderNumber, String shippingCountry, int attempts,
            String error, Instant failedAt) {
    }

    @Operation(summary = "Pedidos cuyo envío no se pudo crear y esperan intervención (admin)")
    @GetMapping("/api/admin/orders/fulfillment-failures")
    public ResponseEntity<List<FailedFulfillmentView>> failures() {
        return ResponseEntity.ok(trackingViewMapper.toFailedViews(fulfillmentService.failedFulfillments()));
    }

    @Operation(summary = "Reintentar la creación del envío tras corregir el motivo del fallo (admin)")
    @PostMapping("/api/admin/orders/{id}/retry-fulfillment")
    public ResponseEntity<TrackingView> retryFulfillment(@PathVariable UUID id) {
        fulfillmentService.retryFulfillment(id);
        // El refresco del seguimiento es un extra: sirve para que el admin vea el estado al instante. Si
        // el transportista no contesta NO puede tumbar la respuesta, porque el reintento ya está hecho y
        // devolver un 500 sobre una operación que ha funcionado invita a pulsar otra vez — y cada
        // pulsación relanza el envío, o sea guías de más pagadas en el carrier. Se ha visto: justo
        // después de crear una guía, YunExpress responde 503 a la consulta de trazabilidad.
        refreshQuietly(id);
        return ResponseEntity.ok(fulfillmentService.adminTrackingView(id));
    }

    /**
     * Sincroniza con el transportista sin dejar que un fallo suyo llegue al cliente de la API. Lo que
     * se devuelve entonces es el último estado conocido, que es exactamente lo que el admin necesita
     * ver; el sondeo periódico lo pondrá al día por su cuenta.
     */
    private void refreshQuietly(UUID id) {
        try {
            syncScheduler.syncOrderById(id);
        } catch (RuntimeException e) {
            log.warn("::> [TRACKING] Reintento hecho, pero no se pudo refrescar el seguimiento pedido={} causa={}",
                    id, e.getMessage());
        }
    }
}
