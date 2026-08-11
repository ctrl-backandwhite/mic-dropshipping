package com.nexaplatform.dropshipping.domain.enums;

/**
 * De dónde sale una reseña.
 *
 * <p>Importa porque decide qué se le puede decir al comprador. Sólo una reseña escrita por alguien que
 * compró aquí puede llevar el distintivo de compra verificada: presentarla como tal sin haber tomado
 * medidas razonables para comprobarlo está en la lista negra de prácticas comerciales desleales de la
 * Directiva Omnibus (UE) 2019/2161, que se sanciona sin necesidad de probar que nadie fue engañado.
 *
 * <p>Las importadas siguen siendo información útil sobre el producto y se muestran, pero declarando su
 * procedencia.
 */
public enum ReviewSource {

    /** Vino en la carga del catálogo del proveedor. Nunca es compra verificada. */
    SUPPLIER,

    /** La escribió un usuario de esta plataforma. */
    CUSTOMER;

    /** Sólo una reseña de un comprador de la plataforma puede llevar el distintivo. */
    public boolean canBeVerified() {
        return this == CUSTOMER;
    }
}
