package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.ShippingQuoteService;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.CainiaoFulfillmentService.SupportedCountry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Cotización de envío de Cainiao para el checkout. El controlador solo mapea la petición y delega en
 * {@link ShippingQuoteService} (la lógica de peso/tarifa vive en la capa de aplicación).
 */
@Tag(name = "Storefront · Shipping", description = "Cotización de envío Cainiao por destino")
@RestController
@RequestMapping("/api/storefront/shipping")
@RequiredArgsConstructor
public class ShippingQuoteController {

    private final ShippingQuoteService shippingQuoteService;

    public record QuoteItem(UUID productId, int quantity) {
    }

    public record QuoteRequest(String country, List<QuoteItem> items) {
    }

    @Operation(summary = "Cotizar el envío a un país para el carrito dado")
    @PostMapping("/quote")
    public ResponseEntity<ShippingQuote> quote(@RequestBody QuoteRequest req) {
        List<ShippingQuoteService.Line> lines = req.items() == null
                ? List.of()
                : req.items().stream().map(i -> new ShippingQuoteService.Line(i.productId(), i.quantity())).toList();
        return ResponseEntity.ok(shippingQuoteService.quote(req.country(), lines));
    }

    @Operation(summary = "Países a los que se puede enviar (cobertura real de Cainiao)")
    @GetMapping("/countries")
    public ResponseEntity<List<SupportedCountry>> supportedCountries() {
        return ResponseEntity.ok(shippingQuoteService.supportedCountries());
    }
}
