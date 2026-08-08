package com.nexaplatform.dropshipping.domain.enums;

/** Sobre qué parte del catálogo alcanza una promoción. */
public enum PromotionScope {

    /** Toda la tienda. No necesita filas en {@code promotion_target}. */
    ALL,
    /** Las categorías listadas en {@code promotion_target}, y sus descendientes. */
    CATEGORY,
    /** Solo los productos listados en {@code promotion_target}. */
    PRODUCT
}
