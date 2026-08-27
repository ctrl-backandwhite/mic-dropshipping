package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Una compra al proveedor tal y como la ve el admin en la cola de trabajo.
 *
 * <p>Trae todo lo necesario para comprar sin salir de la pantalla: el enlace a la ficha de 1688, la
 * variante con su nombre en chino —que es como hay que buscarla en la web del proveedor— y la
 * dirección del almacén con el código de cliente ya pegado.
 */
@Value
@Builder(toBuilder = true)
public class AdminSupplierPurchaseDtoOut {

    UUID id;
    UUID orderId;
    String orderNumber;
    String status;
    UUID supplierId;
    String supplierName;
    String warehouseCode;

    /** Dirección china completa, lista para pegar en el formulario de envío de 1688. */
    String warehouseAddress;

    String purchaseRef;
    BigDecimal costCny;
    BigDecimal shippingCny;
    Instant purchasedAt;

    /**
     * Las cuentas de la compra, ya formateadas.
     *
     * <p>Van como texto porque el importe se calcula y se escribe en el backend: el panel solo pinta.
     * Son null mientras no haya con qué compararlas —sin coste registrado no hay realidad que
     * contrastar—, y entonces el bloque no se pinta.
     */
    String expectedCostCnyFormatted;
    String realCostCnyFormatted;
    /** Diferencia con signo: positiva si se pagó de más. */
    String costVarianceCnyFormatted;
    boolean overBudget;
    String expectedMarginFormatted;
    String realMarginFormatted;
    Integer realMarginPct;

    String domesticTracking;
    String domesticCarrier;
    Instant shippedAt;

    Instant receivedAt;
    String packOrderNo;
    String packServiceType;
    Instant packSubmittedAt;
    /** Cuándo se volcó a un fichero descargado; null = pendiente de exportar. */
    Instant exportedAt;

    /**
     * Días que lleva el bulto en el almacén. A los 30 lo destruyen sin compensación, así que este número
     * es la única alarma real del flujo.
     */
    Integer daysInWarehouse;

    /** Servicio de re-empaquetado que le corresponde según cuántos bultos tenga el pedido. */
    String suggestedServiceType;

    String notes;
    Instant createdAt;

    List<Line> items;

    /** Una línea del pedido que cubre esta compra. */
    @Value
    @Builder(toBuilder = true)
    public static class Line {
        UUID orderItemId;
        String title;
        /** Título original del proveedor: es como aparece en 1688 y evita comprar el producto de al lado. */
        String titleZh;
        String variantName;
        String imageUrl;
        /** Ficha de origen en 1688, para abrirla directamente. */
        String sourceUrl;
        int quantity;
    }
}
