package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin list/detail row for an order. Field names mirror the keys the controller
 * previously put into its ad-hoc {@code Map<String,Object>} so the JSON contract
 * is preserved for the admin frontend.
 */
@Value
@Builder(toBuilder = true)
public class AdminOrderRowDtoOut {

    UUID id;
    String orderNumber;
    String status;
    UUID partnerAppId;
    /** Origen: PLATFORM (tienda propia) o INTEGRATION (tienda conectada). */
    String source;
    int subtotalCents;
    int shippingCents;
    int totalCents;
    String currency;
    /**
     * Total del pedido ya FORMATEADO por el backend en la divisa activa del admin.
     *
     * <p>El listado convertía {@code totalCents} en el navegador y mostraba un céntimo menos de lo
     * cobrado (9,53 € frente a 9,54 €): el cobro se calcula sumando las líneas convertidas y reconvertir
     * el total entero no da lo mismo. Un descuadre así entre lo que ve el admin y lo que pagó el cliente
     * hace impresentable cualquier cuadre de caja.
     */
    String totalFormatted;
    int itemCount;
    Instant placedAt;
    Instant forwardedAt;
    Instant shippedAt;
    Instant deliveredAt;
    Instant cancelledAt;
    String customerEmail;
    String shopName;
    String shopHandle;
    String supplierName;
}
