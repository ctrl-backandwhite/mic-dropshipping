package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

/**
 * A single line item inside the admin order detail. Field names mirror the keys
 * the controller previously placed into its per-line {@code Map<String,Object>}.
 */
@Value
@Builder
public class AdminOrderLineDtoOut {

    UUID id;
    UUID productId;
    String sku;
    String title;
    // Variante seleccionada (color / talla), p. ej. "Negro / M".
    String variantName;
    // Miniatura del producto/variante (snapshot del pedido o imagen viva del catálogo).
    String imageUrl;
    int qty;
    int unitPriceCents;
    int lineTotalCents;
    // URL de la ficha original del producto: el operador la abre para gestionar/procesar la orden.
    String productSourceUrl;
}
