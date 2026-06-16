package com.nexaplatform.dropshipping.domain.enums;

/** Resolution order on lookup: most specific wins. A product group sits between PRODUCT and SUPPLIER. */
public enum PriceRuleScope {
    VARIANT, PRODUCT, PRODUCT_GROUP, SUPPLIER, CATEGORY, GLOBAL
}
