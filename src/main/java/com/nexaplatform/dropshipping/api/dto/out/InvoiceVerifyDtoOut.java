package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Resultado de la verificación pública de una factura (destino del QR). Datos no sensibles que confirman
 * la autenticidad del documento sin exponer información personal del cliente.
 */
@Value
@Builder
public class InvoiceVerifyDtoOut {

    boolean valid;
    String orderNumber;
    String status;
    Instant issuedAt;
    String issuer;
}
