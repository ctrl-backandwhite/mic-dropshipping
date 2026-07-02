package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.InvoiceService;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.application.usecase.CustomerSubscriptionUseCase;
import com.nexaplatform.dropshipping.application.usecase.OrderUseCase;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.User;
import com.nexaplatform.dropshipping.domain.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PaymentJpaRepositoryAdapter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
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
    private final CustomerSubscriptionUseCase customerSubscriptionUseCase;
    private final InvoiceService invoiceService;
    private final UserRepository userRepository;
    private final PaymentJpaRepositoryAdapter paymentRepository;
    private final OrderEmailService orderEmailService;

    @Operation(summary = "Descargar la factura (PDF) del pedido del usuario autenticado")
    @GetMapping("/api/me/orders/{id}/invoice.pdf")
    public ResponseEntity<byte[]> myInvoice(Authentication auth, @PathVariable UUID id,
            @RequestParam(required = false) String lang) {
        UUID userId = UUID.fromString(auth.getName());
        // Si no llega lang, usamos el idioma del usuario para que los títulos (que se re-traducen) y la
        // plantilla salgan en UN único idioma coherente (evita la mezcla español/inglés en la factura).
        String resolved = resolveLang(lang, userId);
        return pdf(orderUseCase.getMyOrderDetail(userId, id, resolved), resolved);
    }

    @Operation(summary = "Descargar la factura (PDF) de cualquier pedido (admin)")
    @GetMapping("/api/admin/orders/{id}/invoice.pdf")
    public ResponseEntity<byte[]> adminInvoice(@PathVariable UUID id, @RequestParam(required = false) String lang) {
        Order order = orderUseCase.getAdminOrderDetail(id, lang);
        String resolved = resolveLang(lang, order.getUserId());
        // Re-resolvemos el detalle con el idioma definitivo para que los títulos coincidan con la plantilla.
        return pdf(orderUseCase.getAdminOrderDetail(id, resolved), resolved);
    }

    @Operation(summary = "Descargar la factura (PDF) de un plan del usuario autenticado (mismo diseño que pedidos)")
    @GetMapping("/api/me/billing/invoices/{number}/invoice.pdf")
    public ResponseEntity<byte[]> myPlanInvoice(Authentication auth, @PathVariable String number,
            @RequestParam(required = false) String lang) throws Exception {
        UUID userId = UUID.fromString(auth.getName());
        String resolved = resolveLang(lang, userId);
        byte[] bytes = customerSubscriptionUseCase.renderInvoicePdf(userId, number, resolved);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"factura-" + number + ".pdf\"")
                .body(bytes);
    }

    /** Idioma efectivo: el pedido por query, o el del usuario, o español por defecto. */
    private String resolveLang(String lang, UUID userId) {
        if (lang != null && !lang.isBlank()) {
            return lang;
        }
        if (userId != null) {
            User u = userRepository.getById(userId);
            if (u != null && u.getLanguage() != null && !u.getLanguage().isBlank()) {
                return u.getLanguage();
            }
        }
        return "es";
    }

    @Operation(summary = "Enviar un email de PRUEBA de la factura de un pedido a una dirección (admin)")
    @PostMapping("/api/admin/orders/{id}/invoice/send-test")
    public ResponseEntity<Map<String, Object>> sendTest(@PathVariable UUID id, @RequestParam String email,
            @RequestParam(required = false) String lang) {
        String resolved = lang != null && !lang.isBlank() ? lang : "es";
        Order o = orderUseCase.getAdminOrderDetail(id, resolved);
        // Reutiliza el mismo email de factura (mismo diseño/idioma/moneda) enviándolo a la dirección de prueba.
        // Usamos el método de pago REAL del pedido (traducido en el email); si no hay pago registrado
        // (p. ej. pago con wallet o pedido de prueba), mostramos CARD como método representativo.
        String method = paymentRepository.findByOrderIdOrderByCreatedAtDesc(id).stream()
                .map(p -> p.getMethod()).filter(m -> m != null).map(m -> m.name())
                .findFirst().orElse("CARD");
        orderEmailService.paymentConfirmed(o, email, resolved, method, invoiceCurrency(o));
        return ResponseEntity.ok(Map.of("sent", true, "to", email, "order", o.getOrderNumber()));
    }

    private ResponseEntity<byte[]> pdf(Order o, String lang) {
        // Moneda de la factura = la del pago (EUR si se pagó en EUR, USD en otro caso), para que el PDF
        // coincida con el email y con lo que el cliente pagó. Fallback al USD canónico del pedido.
        byte[] bytes = invoiceService.renderPdf(o, lang, invoiceCurrency(o));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"factura-" + o.getOrderNumber() + ".pdf\"")
                .body(bytes);
    }

    /** Moneda de liquidación del pago del pedido (la pagada); USD si no hay pago o es cripto. */
    private String invoiceCurrency(Order o) {
        return paymentRepository.findSettlementCurrenciesByOrderId(o.getId()).stream()
                .filter(c -> c != null && !c.isBlank() && !"USDT".equalsIgnoreCase(c)).findFirst()
                .orElse(o.getCurrency() != null ? o.getCurrency() : "USD");
    }
}
