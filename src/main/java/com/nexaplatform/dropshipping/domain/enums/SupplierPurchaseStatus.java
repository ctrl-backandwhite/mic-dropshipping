package com.nexaplatform.dropshipping.domain.enums;

/**
 * Situación de la compra al proveedor, del «hay que comprarlo» al bulto ya reempaquetado.
 *
 * <p>Igual que en {@link OrderStatus}, cada estado lleva su avance y hay que comparar por
 * {@link #progress()} y nunca por {@code ordinal()}: CANCELLED se declara al final pero no es el estado
 * más avanzado. Esto importa porque un pedido va tan atrasado como su compra más atrasada — con dos
 * proveedores, uno que ya envió y otro que ni se ha comprado, el pedido está sin comprar.
 */
public enum SupplierPurchaseStatus {

    /** El cliente ha pagado; falta comprar el producto en 1688. */
    PENDING(0),
    /** Comprado y pagado al proveedor; todavía no lo ha despachado. */
    PURCHASED(1),
    /** El proveedor lo envió al almacén chino: ya hay número de seguimiento nacional. */
    IN_TRANSIT(2),
    /** El almacén lo ha recibido. Desde aquí corren los 30 días hasta la destrucción. */
    AT_WAREHOUSE(3),
    /** Orden de re-empaquetado dada de alta en Yunfulfillment. */
    PACKED(4),
    /** Fuera del recorrido: no puede ganar a una compra que sí avanza, de ahí el avance negativo. */
    CANCELLED(-1);

    private final int progress;

    SupplierPurchaseStatus(int progress) {
        this.progress = progress;
    }

    /** Cuánto ha avanzado la compra. Úsese esto para comparar, nunca {@code ordinal()}. */
    public int progress() {
        return progress;
    }

    /**
     * ¿El bulto está ya camino del almacén o dentro de él?
     *
     * <p>Es la condición para poder crear la guía internacional: sin mercancía en movimiento, la guía
     * es una promesa que arranca el reloj del seguimiento sobre un paquete que no existe.
     */
    public boolean merchandiseOnTheMove() {
        return progress >= IN_TRANSIT.progress;
    }

    /**
     * ¿El dinero ya ha salido hacia el proveedor?
     *
     * <p>Es la frontera del desistimiento del cliente. Mientras la compra sigue en {@link #PENDING} no
     * se ha gastado nada y cancelar solo cuesta devolver el cobro; a partir de {@link #PURCHASED} el
     * género está pagado en 1688 y ya no se puede recuperar, así que un reembolso ahí es pérdida
     * íntegra para el comercio.
     *
     * <p>{@link #CANCELLED} queda fuera por su avance negativo: una compra descartada no compromete
     * nada.
     */
    public boolean alreadyBought() {
        return progress >= PURCHASED.progress;
    }
}
