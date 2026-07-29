package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.ParcelSpec;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;

/**
 * Arma el bulto que se manda a cotizar a partir de las líneas del carrito o del pedido: peso, medidas
 * y presencia de batería.
 *
 * <p>Existe para que la <b>vista previa del checkout</b> ({@link ShippingQuoteService}) y el <b>cobro</b>
 * ({@code OrderUseCaseImpl}) construyan el bulto EXACTAMENTE igual. Si divergen, el cliente ve un importe
 * de envío y se le cobra otro.
 *
 * <p>Medidas: se toman las del PRODUCTO, que son las del paquete (sembradas desde
 * {@code category_customs_profile}); las de la variante describen el artículo, no el embalaje, y solo se
 * usan si el producto no las trae. Al apilar varias unidades se conserva el mayor largo y el mayor ancho
 * y se suman las alturas, que es la aproximación conservadora habitual para un bulto único.
 */
public final class ParcelAggregator {

    /** Peso por unidad cuando ni la variante ni el producto lo traen (no debería ocurrir: el catálogo lo exige). */
    private static final int FALLBACK_WEIGHT_GRAMS = 500;

    private int weightGrams;
    private int maxLengthMm;
    private int maxWidthMm;
    private int totalHeightMm;
    private boolean withBattery;

    /** Añade {@code quantity} unidades de un producto (con su variante, si la hay) al bulto. */
    public void add(ProductEntity product, ProductVariantEntity variant, int quantity) {
        int qty = Math.max(1, quantity);
        weightGrams = Math.addExact(weightGrams, Math.multiplyExact(unitWeightGrams(product, variant), qty));

        int length = dimension(product.getLengthMm(), variant != null ? variant.getLengthMm() : null);
        int width = dimension(product.getWidthMm(), variant != null ? variant.getWidthMm() : null);
        int height = dimension(product.getHeightMm(), variant != null ? variant.getHeightMm() : null);
        if (length > 0 && width > 0 && height > 0) {
            maxLengthMm = Math.max(maxLengthMm, length);
            maxWidthMm = Math.max(maxWidthMm, width);
            totalHeightMm = Math.addExact(totalHeightMm, Math.multiplyExact(height, qty));
        }
        if (hasBattery(product)) {
            withBattery = true;
        }
    }

    /**
     * Añade {@code quantity} unidades de una línea cuyo producto no se pudo resolver. Se cuenta con el
     * peso por defecto en vez de ignorarla: dejarla fuera cotizaría el envío casi gratis.
     */
    public void addUnknown(int quantity) {
        weightGrams = Math.addExact(weightGrams,
                Math.multiplyExact(FALLBACK_WEIGHT_GRAMS, Math.max(1, quantity)));
    }

    /** El bulto agregado, listo para {@code FulfillmentProvider.quote}. */
    public ParcelSpec build() {
        return new ParcelSpec(Math.max(1, weightGrams), maxLengthMm, maxWidthMm, totalHeightMm, withBattery);
    }

    /** Peso a facturar por unidad: peso del paquete de la variante, luego neto, luego el del producto. */
    public static int unitWeightGrams(ProductEntity product, ProductVariantEntity variant) {
        if (variant != null) {
            if (variant.getPackageWeightGrams() != null && variant.getPackageWeightGrams() > 0) {
                return variant.getPackageWeightGrams();
            }
            if (variant.getWeightGrams() != null && variant.getWeightGrams() > 0) {
                return variant.getWeightGrams();
            }
        }
        if (product.getPackageWeightGrams() != null && product.getPackageWeightGrams() > 0) {
            return product.getPackageWeightGrams();
        }
        if (product.getWeightGrams() != null && product.getWeightGrams() > 0) {
            return product.getWeightGrams();
        }
        // El peso del catálogo vive en las VARIANTES (la báscula es por SKU), no a nivel producto. Si aún
        // no hay variante elegida —vista previa del envío antes de escoger color/talla— se toma la más
        // pesada en vez del valor por defecto: es el dato real del producto y no infravalora el flete.
        int heaviestVariant = 0;
        if (product.getVariants() != null) {
            for (ProductVariantEntity v : product.getVariants()) {
                Integer grams = v.getPackageWeightGrams() != null && v.getPackageWeightGrams() > 0
                        ? v.getPackageWeightGrams() : v.getWeightGrams();
                if (grams != null && grams > heaviestVariant) {
                    heaviestVariant = grams;
                }
            }
        }
        return heaviestVariant > 0 ? heaviestVariant : FALLBACK_WEIGHT_GRAMS;
    }

    /** ¿El producto lleva batería? Determina el {@code PackageType} de YunExpress (0 普货 / 1 带电). */
    public static boolean hasBattery(ProductEntity product) {
        String type = product.getBatteryType();
        return type != null && !type.isBlank() && !"NONE".equalsIgnoreCase(type.trim());
    }

    private static int dimension(Integer productValue, Integer variantValue) {
        if (productValue != null && productValue > 0) {
            return productValue;
        }
        return variantValue != null && variantValue > 0 ? variantValue : 0;
    }
}
