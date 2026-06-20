package com.nexaplatform.dropshipping.domain.enums;

/**
 * Resolution order on lookup: most specific wins. A product group sits between PRODUCT and SUPPLIER.
 * CATEGORY_GROUP (una regla que agrupa varias categorías en una sola) va tras CATEGORY (menos específico
 * que una categoría concreta) y antes de GLOBAL.
 */
public enum PriceRuleScope {
    VARIANT, PRODUCT, PRODUCT_GROUP, SUPPLIER, CATEGORY, CATEGORY_GROUP, GLOBAL
}
