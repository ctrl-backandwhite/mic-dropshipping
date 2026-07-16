package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
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

    /** Resultado de crear el envío: transportista + nº de seguimiento + referencia + ETA máx (días). */
    record FulfillmentResult(String carrier, String trackingNumber, String fulfillmentRef, int etaMaxDays) {
    }

    /** Un paso de la línea temporal de tracking. */
    record TrackingStep(OrderStatus status, String description, String location, Instant occurredAt) {
    }

    /** Instantánea de tracking: estado actual del envío + pasos ocurridos hasta ahora. */
    record TrackingSnapshot(OrderStatus currentStatus, List<TrackingStep> steps) {
    }

    /** ¿El proveedor envía a este país? */
    boolean isSupported(String countryCode);

    /** Países cubiertos (habilitados), ordenados por nombre. */
    List<SupportedCountry> supportedCountries();

    /** Cotiza el envío a un destino para un peso total (gramos); {@link ShippingQuote#unsupported} si no cubre. */
    ShippingQuote quote(String countryCode, int totalWeightGrams);

    /** Crea el envío al despachar el pedido (devuelve carrier + nº de seguimiento + referencia). */
    FulfillmentResult createShipment(Order order);

    /** Consulta el tracking del envío (estado actual + pasos). */
    TrackingSnapshot track(String trackingNumber, Instant forwardedAt, String countryCode);
}
