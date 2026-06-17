package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.CainiaoFulfillmentService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Cotización de envío para el checkout: resuelve el peso del carrito (peso del paquete por producto) y
 * delega en Cainiao la tarifa por destino. La lógica vive aquí (capa de aplicación), no en el controlador.
 */
@Service
@RequiredArgsConstructor
public class ShippingQuoteService {

    private final ProductRepository productRepository;
    private final CainiaoFulfillmentService cainiao;

    /** Una línea del carrito a cotizar. */
    public record Line(UUID productId, int quantity) {
    }

    /** Cotiza el envío a {@code country} para las líneas dadas (suma el peso real de cada producto). */
    public ShippingQuote quote(String country, List<Line> lines) {
        int weightGrams = 0;
        if (lines != null) {
            for (Line line : lines) {
                int unit = productRepository.findById(line.productId()).map(this::packageWeight).orElse(500);
                weightGrams += unit * Math.max(1, line.quantity());
            }
        }
        return cainiao.quote(country, Math.max(1, weightGrams));
    }

    private int packageWeight(ProductEntity p) {
        if (p.getPackageWeightGrams() != null && p.getPackageWeightGrams() > 0) {
            return p.getPackageWeightGrams();
        }
        if (p.getWeightGrams() != null && p.getWeightGrams() > 0) {
            return p.getWeightGrams();
        }
        return 500;
    }
}
