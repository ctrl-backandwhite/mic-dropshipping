package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.CainiaoFulfillmentService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Cotización de envío de Cainiao para el checkout: dado el país de destino y el carrito, devuelve si el
 * destino está soportado, la tarifa estimada (USD céntimos) y la ventana de entrega. Permite al frontend
 * mostrar el coste de envío y avisar si el país no está cubierto antes de pagar.
 */
@Tag(name = "Storefront · Shipping", description = "Cotización de envío Cainiao por destino")
@RestController
@RequestMapping("/api/storefront/shipping")
@RequiredArgsConstructor
public class ShippingQuoteController {

    private final ProductRepository productRepository;
    private final CainiaoFulfillmentService cainiao;

    public record QuoteItem(UUID productId, int quantity) {
    }

    public record QuoteRequest(String country, List<QuoteItem> items) {
    }

    @Operation(summary = "Cotizar el envío a un país para el carrito dado")
    @PostMapping("/quote")
    public ResponseEntity<ShippingQuote> quote(@RequestBody QuoteRequest req) {
        int weight = 0;
        if (req.items() != null) {
            for (QuoteItem it : req.items()) {
                int unit = productRepository.findById(it.productId()).map(this::weightOf).orElse(500);
                weight += unit * Math.max(1, it.quantity());
            }
        }
        return ResponseEntity.ok(cainiao.quote(req.country(), Math.max(1, weight)));
    }

    private int weightOf(ProductEntity p) {
        if (p.getPackageWeightGrams() != null && p.getPackageWeightGrams() > 0) {
            return p.getPackageWeightGrams();
        }
        if (p.getWeightGrams() != null && p.getWeightGrams() > 0) {
            return p.getWeightGrams();
        }
        return 500;
    }
}
