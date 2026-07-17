package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
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
    private final FulfillmentProvider fulfillment;

    /** Una línea del carrito a cotizar. {@code variantId} puede ser null (producto sin variantes). */
    public record Line(UUID productId, UUID variantId, int quantity) {
    }

    /** Países a los que Cainiao envía (para el banner de cobertura de la home). */
    public List<FulfillmentProvider.SupportedCountry> supportedCountries() {
        return fulfillment.supportedCountries();
    }

    /** Cotiza el envío a {@code country} para las líneas dadas (suma el peso real de cada producto). */
    public ShippingQuote quote(String country, List<Line> lines) {
        int weightGrams = 0;
        if (lines != null) {
            for (Line line : lines) {
                int unit = productRepository.findById(line.productId())
                        .map(p -> packageWeight(p, line.variantId()))
                        .orElse(500);
                weightGrams += unit * Math.max(1, line.quantity());
            }
        }
        return fulfillment.quote(country, Math.max(1, weightGrams));
    }

    /**
     * Peso del paquete a facturar por unidad: prioriza el peso REAL de la variante SELECCIONADA
     * (báscula 1688: package&gt;neto), luego el peso a nivel producto y, en último caso, 500 g.
     */
    private int packageWeight(ProductEntity p, UUID variantId) {
        if (variantId != null && p.getVariants() != null) {
            ProductVariantEntity v = p.getVariants().stream()
                    .filter(x -> variantId.equals(x.getId())).findFirst().orElse(null);
            if (v != null) {
                if (v.getPackageWeightGrams() != null && v.getPackageWeightGrams() > 0) {
                    return v.getPackageWeightGrams();
                }
                if (v.getWeightGrams() != null && v.getWeightGrams() > 0) {
                    return v.getWeightGrams();
                }
            }
        }
        if (p.getPackageWeightGrams() != null && p.getPackageWeightGrams() > 0) {
            return p.getPackageWeightGrams();
        }
        if (p.getWeightGrams() != null && p.getWeightGrams() > 0) {
            return p.getWeightGrams();
        }
        return 500;
    }
}
