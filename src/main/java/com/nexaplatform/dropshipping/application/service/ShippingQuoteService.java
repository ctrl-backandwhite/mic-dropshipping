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

    /**
     * Cotiza el envío a {@code country} para las líneas dadas. El bulto (peso, medidas del paquete y
     * batería) se arma con {@link ParcelAggregator}, el mismo que usa el cobro del pedido, para que la
     * vista previa del checkout y el importe cobrado no puedan divergir.
     */
    public ShippingQuote quote(String country, List<Line> lines) {
        ParcelAggregator parcel = new ParcelAggregator();
        if (lines != null) {
            for (Line line : lines) {
                ProductEntity product = productRepository.findById(line.productId()).orElse(null);
                if (product != null) {
                    parcel.add(product, variantOf(product, line.variantId()), line.quantity());
                } else {
                    parcel.addUnknown(line.quantity());
                }
            }
        }
        return fulfillment.quote(country, parcel.build());
    }

    /** La variante seleccionada de la línea, o {@code null} si el producto no tiene variantes. */
    private ProductVariantEntity variantOf(ProductEntity product, UUID variantId) {
        if (variantId == null || product.getVariants() == null) {
            return null;
        }
        return product.getVariants().stream().filter(v -> variantId.equals(v.getId())).findFirst().orElse(null);
    }
}
