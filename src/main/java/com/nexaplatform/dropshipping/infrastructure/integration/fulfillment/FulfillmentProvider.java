package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.enums.TaxMode;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;

import java.time.Instant;
import java.util.List;

/**
 * Puerto del proveedor de fulfillment/logística (cobertura, cotización, crear envío, tracking).
 *
 * <p>Abstrae al transportista para poder conmutar de proveedor sin tocar los consumidores
 * ({@code FulfillmentService}, {@code OrderUseCaseImpl}, {@code ShippingQuoteService},
 * {@code ShippingQuoteController}). La implementación activa hoy es {@link YunExpressFulfillmentService}
 * (única del tipo). {@code CainiaoFulfillmentService} queda fuera del flujo (no implementa este puerto).
 */
public interface FulfillmentProvider {

    /** Destino soportado: ISO-2 + nombre (para el banner de cobertura). */
    record SupportedCountry(String countryCode, String countryName) {
    }

    /**
     * Resultado de crear el envío de UN bulto: transportista, nº de seguimiento, referencia (guía) y ETA
     * máx en días. {@code sequenceNo} indica qué bulto del pedido es (1..N) y {@code weightGrams} /
     * {@code declaredValueCents} lo que finalmente viajó en él, que es lo que se enseña al cliente.
     */
    record FulfillmentResult(String carrier, String trackingNumber, String fulfillmentRef, int etaMaxDays,
            int sequenceNo, int weightGrams, int declaredValueCents, String productCode) {

        /** Bulto único de un pedido que no hizo falta repartir. */
        public FulfillmentResult(String carrier, String trackingNumber, String fulfillmentRef, int etaMaxDays) {
            this(carrier, trackingNumber, fulfillmentRef, etaMaxDays, 1, 0, 0, null);
        }
    }

    /** Un paso de la línea temporal de tracking. */
    record TrackingStep(OrderStatus status, String description, String location, Instant occurredAt) {
    }

    /** Instantánea de tracking: estado actual del envío + pasos ocurridos hasta ahora. */
    record TrackingSnapshot(OrderStatus currentStatus, List<TrackingStep> steps) {
    }

    /**
     * Bulto a cotizar. Es lo que el transportista necesita para tarificar: peso real, dimensiones del
     * paquete —de las que sale el peso VOLUMÉTRICO— y si lleva batería, que en YunExpress es el
     * {@code PackageType} (0 = 普货 carga general, 1 = 带电 con batería) y cambia de canal y de tarifa.
     *
     * <p>Las dimensiones van en milímetros para no perder precisión con las medidas de catálogo; el
     * proveedor las convierte a la unidad de su API (YunExpress las quiere en cm enteros).
     */
    record ParcelSpec(int weightGrams, int lengthMm, int widthMm, int heightMm, boolean withBattery) {

        /** Bulto del que solo se conoce el peso (sin dimensiones ni batería declarada). */
        public static ParcelSpec ofWeight(int weightGrams) {
            return new ParcelSpec(weightGrams, 0, 0, 0, false);
        }

        /** ¿Tenemos las tres medidas para calcular volumen? */
        public boolean hasDimensions() {
            return lengthMm > 0 && widthMm > 0 && heightMm > 0;
        }

        /** Volumen del bulto en cm³ (0 si falta alguna medida). */
        public double volumeCm3() {
            return hasDimensions() ? (lengthMm / 10.0) * (widthMm / 10.0) * (heightMm / 10.0) : 0.0;
        }
    }

    /** ¿El proveedor envía a este país? */
    boolean isSupported(String countryCode);

    /** Países cubiertos (habilitados), ordenados por nombre. */
    List<SupportedCountry> supportedCountries();

    /** Cotiza el envío de un bulto a un destino; {@link ShippingQuote#unsupported} si no cubre. */
    ShippingQuote quote(String countryCode, ParcelSpec parcel);

    /**
     * Cotización solo por peso, sin dimensiones ni batería. Se factura por el peso real, así que el
     * importe puede quedarse corto frente al repeso del transportista en un bulto voluminoso: usar la
     * sobrecarga con {@link ParcelSpec} siempre que se conozcan las medidas.
     */
    default ShippingQuote quote(String countryCode, int totalWeightGrams) {
        return quote(countryCode, ParcelSpec.ofWeight(totalWeightGrams));
    }

    /** Crea el envío al despachar el pedido (devuelve carrier + nº de seguimiento + referencia). */
    FulfillmentResult createShipment(Order order);

    /**
     * Crea TODOS los envíos que necesita el pedido: uno por bulto.
     *
     * <p>Un pedido no siempre cabe en un paquete —cada canal impone peso y valor máximos—, así que se
     * reparte y cada bulto viaja con su propia guía. Por defecto se delega en {@link #createShipment} y
     * sale un único envío, que es el comportamiento de un proveedor que no sepa repartir.
     */
    default List<FulfillmentResult> createShipments(Order order) {
        return List.of(createShipment(order));
    }

    /** Consulta el tracking del envío (estado actual + pasos). */
    TrackingSnapshot track(String trackingNumber, Instant forwardedAt, String countryCode);

    /**
     * Modo de despacho fiscal del destino: {@link TaxMode#DDP} (el impuesto se cobra en el checkout y lo
     * liquida el transportista, sin cargo sorpresa para el comprador) o {@link TaxMode#DDU} (lo paga el
     * destinatario en destino).
     */
    TaxMode taxModeFor(String countryCode);
}
