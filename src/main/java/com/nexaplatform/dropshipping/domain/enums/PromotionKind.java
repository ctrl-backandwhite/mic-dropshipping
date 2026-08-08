package com.nexaplatform.dropshipping.domain.enums;

/**
 * Para qué sirve una promoción. No cambia el cálculo —eso lo deciden el porcentaje y el alcance—, pero
 * sí lo que ve el cliente: una rebaja de temporada se anuncia en el catálogo y un cupón no aparece
 * hasta que alguien lo teclea.
 */
public enum PromotionKind {

    /** Rebajas de temporada: invierno, verano, primavera, Black Friday. Se aplican solas. */
    SEASONAL(true),
    /** Oferta relámpago, con vigencia corta. También automática. */
    FLASH(true),
    /** Liquidación de stock. Automática. */
    CLEARANCE(true),
    /** Cupón: el cliente escribe el código en el checkout. */
    COUPON(false),
    /** Descuento por venir de un afiliado. Lo aplica el código de referido, no el cliente. */
    REFERRAL(false);

    private final boolean automatic;

    PromotionKind(boolean automatic) {
        this.automatic = automatic;
    }

    /**
     * ¿Se aplica sola, sin que el cliente escriba nada?
     *
     * <p>Es lo que decide si el precio rebajado sale ya en el catálogo y en la ficha. Un cupón no
     * puede anunciarse ahí: rebajaría el precio de todo el escaparate sin que nadie lo haya canjeado.
     */
    public boolean isAutomatic() {
        return automatic;
    }
}
