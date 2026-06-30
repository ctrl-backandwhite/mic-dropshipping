package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.dto.out.InvoiceVerifyDtoOut;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Verificación PÚBLICA de la autenticidad de una factura: la URL que codifica el código QR del PDF.
 * Devuelve si el pedido existe y sus datos no sensibles (número, estado, fecha). Está bajo
 * {@code /api/v1/storefront/**}, que es público (permitAll) — no expone datos personales del cliente.
 */
@Tag(name = "Invoice verification", description = "Verificación pública de facturas (QR)")
@RestController
@RequestMapping("/api/v1/storefront/invoices")
@RequiredArgsConstructor
public class PublicInvoiceVerifyController {

    private final OrderRepository orderRepository;

    @Operation(summary = "Verificar la autenticidad de una factura por su número de pedido (destino del QR)")
    @GetMapping("/{orderNumber}/verify")
    @Transactional(readOnly = true) // mantiene la sesión abierta al mapear el pedido (evita LazyInit en items)
    public ResponseEntity<InvoiceVerifyDtoOut> verify(@PathVariable String orderNumber) {
        Optional<Order> found = orderRepository.findByOrderNumber(orderNumber);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(InvoiceVerifyDtoOut.builder().valid(false).orderNumber(orderNumber).build());
        }
        Order o = found.get();
        return ResponseEntity.ok(InvoiceVerifyDtoOut.builder()
                .valid(true)
                .orderNumber(o.getOrderNumber())
                .status(o.getStatus() != null ? o.getStatus().name() : null)
                .issuedAt(o.getPlacedAt() != null ? o.getPlacedAt() : o.getCreatedAt())
                .issuer("NX036 Dropshipping")
                .build());
    }
}
