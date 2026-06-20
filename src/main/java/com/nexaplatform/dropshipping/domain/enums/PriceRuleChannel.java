package com.nexaplatform.dropshipping.domain.enums;

/**
 * Canal al que aplica una regla de margen.
 *
 * <ul>
 *   <li>{@link #STOREFRONT}: tienda propia (web/app NX036). Es el valor por defecto.</li>
 *   <li>{@link #INTEGRATION}: apps conectadas por API/integración (Shopify, WooCommerce, …). Permite
 *       aplicar un margen distinto (p. ej. 75%) cuando el precio se sirve a una integración, sin afectar
 *       al margen del storefront (p. ej. 150%).</li>
 * </ul>
 */
public enum PriceRuleChannel {
    STOREFRONT, INTEGRATION
}
