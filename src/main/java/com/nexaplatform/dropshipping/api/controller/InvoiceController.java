package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.InvoiceService;
import com.nexaplatform.dropshipping.application.usecase.OrderUseCase;
import com.nexaplatform.dropshipping.domain.model.Order;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Descarga de la FACTURA de un pedido en PDF. El cliente descarga la suya (valida propiedad vía el use
 * case); el admin la de cualquier pedido. El cuerpo del email de pago incluye la factura en HTML y un
 * enlace a estos endpoints.
 */
@Tag(name = "Invoices", description = "Factura del pedido en PDF")
@RestController
@RequiredArgsConstructor
public class InvoiceController {

    private final OrderUseCase orderUseCase;
    private final InvoiceService invoiceService;

    @Operation(summary = "Descargar la factura (PDF) del pedido del usuario autenticado")
    @GetMapping("/api/me/orders/{id}/invoice.pdf")
    public ResponseEntity<byte[]> myInvoice(Authentication auth, @PathVariable UUID id,
            @RequestParam(required = false) String lang) {
        UUID userId = UUID.fromString(auth.getName());
        return pdf(orderUseCase.getMyOrderDetail(userId, id, lang), lang);
    }

    @Operation(summary = "Descargar la factura (PDF) de cualquier pedido (admin)")
    @GetMapping("/api/admin/orders/{id}/invoice.pdf")
    public ResponseEntity<byte[]> adminInvoice(@PathVariable UUID id, @RequestParam(required = false) String lang) {
        return pdf(orderUseCase.getAdminOrderDetail(id, lang), lang);
    }

    private ResponseEntity<byte[]> pdf(Order o, String lang) {
        byte[] bytes = invoiceService.renderPdf(o, lang);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"factura-" + o.getOrderNumber() + ".pdf\"")
                .body(bytes);
    }
}
