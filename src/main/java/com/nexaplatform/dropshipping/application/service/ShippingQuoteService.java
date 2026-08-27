package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Cotización de envío para el checkout: resuelve el peso del carrito —el del paquete de cada producto—
 * y pregunta la tarifa a los transportistas.
 *
 * <p>Desde el 18-ago-2026 no pregunta a uno solo. La cotización pasa por {@link FulfillmentRouter}, que
 * consulta a todos los que puedan llevar ESE pedido y devuelve una única lista ordenada por precio: qué
 * se envía decide quién puede llevarlo, porque la línea de ropa de YunExpress solo admite textil.
 *
 * <p>La lógica vive aquí, en la capa de aplicación, y no en el controlador.
 */
@Service
@RequiredArgsConstructor
public class ShippingQuoteService {

    private final ProductRepository productRepository;
    /**
     * Solo para la lista de países de la portada. Cotizar ya NO pasa por aquí: desde que hay dos
     * transportistas lo hace {@link FulfillmentRouter}, que pregunta a todos los que puedan llevar el
     * pedido en vez de a uno fijo.
     */
    private final FulfillmentProvider fulfillment;
    private final FulfillmentRouter router;

    /** Una línea del carrito a cotizar. {@code variantId} puede ser null (producto sin variantes). */
    public record Line(UUID productId, UUID variantId, int quantity) {
    }

    /** Países cubiertos, para el aviso de cobertura de la portada. */
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
        // Los productos del carrito se guardan además de pesarlos: desde que hay dos transportistas, QUÉ
        // se envía decide QUIÉN puede llevarlo. La línea de ropa de YunExpress solo admite textil, así
        // que sin esta lista se ofrecerían formas de envío que el transportista rechaza al despachar.
        List<ProductEntity> productos = new ArrayList<>();
        if (lines != null) {
            for (Line line : lines) {
                ProductEntity product = productRepository.findById(line.productId()).orElse(null);
                if (product != null) {
                    productos.add(product);
                    parcel.add(product, variantOf(product, line.variantId()), line.quantity());
                } else {
                    parcel.addUnknown(line.quantity());
                }
            }
        }
        return router.cotizar(country, parcel.build(), productos);
    }

    /** La variante seleccionada de la línea, o {@code null} si el producto no tiene variantes. */
    private ProductVariantEntity variantOf(ProductEntity product, UUID variantId) {
        if (variantId == null || product.getVariants() == null) {
            return null;
        }
        return product.getVariants().stream().filter(v -> variantId.equals(v.getId())).findFirst().orElse(null);
    }
}
