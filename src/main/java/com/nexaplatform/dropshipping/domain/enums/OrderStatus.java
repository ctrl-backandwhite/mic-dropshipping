package com.nexaplatform.dropshipping.domain.enums;

/**
 * Situación de un pedido, del carrito a la entrega.
 *
 * <p>Cada estado lleva su avance ({@link #progress()}) porque el orden de declaración NO sirve para
 * comparar: CANCELLED y REFUNDED están escritos detrás de DELIVERED, así que un {@code ordinal()}
 * mayor no significa «más avanzado». Al agregar el estado de un pedido a partir de sus bultos —el
 * pedido va tan atrasado como su bulto más atrasado— comparar por posición dejaba un bulto devuelto
 * como ENTREGADO, que es justo lo contrario de lo que pasó.
 */
public enum OrderStatus {

    PENDING(0), AWAITING_PAYMENT(1), PAID(2), FORWARDED(3), SHIPPED(4), DELIVERED(5),
    /** Fuera del recorrido normal: no puede ganar a un bulto que sí avanza, de ahí el avance negativo. */
    CANCELLED(-1), REFUNDED(-1);

    private final int progress;

    OrderStatus(int progress) {
        this.progress = progress;
    }

    /** Cuánto ha avanzado el pedido. Úsese esto para comparar, nunca {@code ordinal()}. */
    public int progress() {
        return progress;
    }
}
