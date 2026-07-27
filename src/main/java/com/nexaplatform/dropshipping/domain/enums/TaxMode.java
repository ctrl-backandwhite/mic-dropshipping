package com.nexaplatform.dropshipping.domain.enums;

/**
 * Modo de despacho fiscal del envío internacional.
 *
 * <ul>
 *   <li>{@link #DDP} — <i>Delivered Duty Paid</i>: el impuesto se cobra al cliente en el checkout y lo
 *       liquida el transportista en destino a cargo del comercio. El comprador no recibe ningún cargo
 *       sorpresa en aduana.</li>
 *   <li>{@link #DDU} — <i>Delivered Duty Unpaid</i>: el impuesto lo paga el destinatario al recibir. Solo
 *       para destinos donde el transportista no ofrece línea DDP.</li>
 * </ul>
 */
public enum TaxMode {

    DDP,
    DDU;

    /** Convierte el texto persistido/configurado; cualquier valor desconocido o nulo cae a {@link #DDP}. */
    public static TaxMode from(String value) {
        if (value == null || value.isBlank()) {
            return DDP;
        }
        try {
            return TaxMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return DDP;
        }
    }
}
