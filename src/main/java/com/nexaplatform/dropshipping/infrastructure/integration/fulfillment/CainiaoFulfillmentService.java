package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CainiaoZoneEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CainiaoZoneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Conector de fulfillment con <b>Cainiao</b> (菜鸟, red logística de Alibaba/AliExpress).
 *
 * <p>Responsabilidades:
 * <ul>
 *   <li><b>Cobertura + tarifa por destino</b>: solo se envía a países en {@code cainiao_shipping_zone};
 *       la tarifa = base + por-kg del país.</li>
 *   <li><b>Crear el envío</b> al despachar el pedido (devuelve carrier + nº de seguimiento + ref Cainiao).</li>
 *   <li><b>Tracking</b>: línea temporal de eventos + estado actual del envío.</li>
 * </ul>
 *
 * <p>Mock-first (como Stripe): con {@code nexadrop.cainiao.enabled=false} (por defecto) genera datos
 * deterministas — tarifa real de la tabla de zonas y un tracking simulado que avanza con el tiempo — de
 * modo que el flujo completo (despacho → en camino → entregado) funciona sin credenciales. Con
 * credenciales reales se sustituirían las ramas mock por llamadas a la Cainiao Open Platform.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CainiaoFulfillmentService {

    private final CainiaoZoneRepository zoneRepository;

    @Value("${nexadrop.cainiao.enabled:false}")
    private boolean enabled;
    @Value("${nexadrop.cainiao.app-key:}")
    private String appKey;
    @Value("${nexadrop.cainiao.platform-id:nexadrop-dropshipping}")
    private String platformId;
    /** Minutos por etapa del tracking simulado (mock). Permite ver el avance del estado en una demo. */
    @Value("${nexadrop.cainiao.mock-stage-minutes:2}")
    private long mockStageMinutes;

    private boolean isActive() {
        return enabled && appKey != null && !appKey.isBlank();
    }

    /** ¿Cainiao envía a este país? */
    public boolean isSupported(String countryCode) {
        return zone(countryCode).isPresent();
    }

    /** Destino soportado por Cainiao: código ISO-2 + nombre, para pintar el banner de cobertura. */
    public record SupportedCountry(String countryCode, String countryName) {
    }

    /** Países a los que Cainiao envía (zonas habilitadas), ordenados por nombre. */
    public List<SupportedCountry> supportedCountries() {
        return zoneRepository.findByEnabledTrueOrderByCountryNameAsc().stream()
                .map(z -> new SupportedCountry(z.getCountryCode(), z.getCountryName())).toList();
    }

    private Optional<CainiaoZoneEntity> zone(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) {
            return Optional.empty();
        }
        return zoneRepository.findByCountryCodeIgnoreCase(countryCode.trim()).filter(CainiaoZoneEntity::isEnabled);
    }

    /**
     * Cotiza el envío a un destino para un peso total (gramos). Devuelve {@link ShippingQuote#unsupported}
     * si el país no está cubierto por Cainiao.
     */
    public ShippingQuote quote(String countryCode, int totalWeightGrams) {
        Optional<CainiaoZoneEntity> z = zone(countryCode);
        if (z.isEmpty()) {
            return ShippingQuote.unsupported(countryCode);
        }
        CainiaoZoneEntity zn = z.get();
        double kg = Math.max(0.1, totalWeightGrams / 1000.0);
        int amount = zn.getBaseCents() + (int) Math.round(zn.getPerKgCents() * kg);
        return new ShippingQuote(true, zn.getCountryCode(), amount, "Standard Shipping", "Standard Shipping",
                zn.getEtaMinDays(), zn.getEtaMaxDays(), zn.getZone());
    }

    /** Resultado de crear el envío en Cainiao. */
    public record FulfillmentResult(String carrier, String trackingNumber, String fulfillmentRef, int etaMaxDays) {
    }

    /**
     * Crea el envío en Cainiao al despachar el pedido. Devuelve el nº de seguimiento + la referencia
     * logística. En mock genera identificadores deterministas a partir del id del pedido.
     */
    public FulfillmentResult createShipment(Order order) {
        int etaMax = zone(order.getShippingCountry()).map(CainiaoZoneEntity::getEtaMaxDays).orElse(20);
        if (!isActive()) {
            String hex = order.getId().toString().replace("-", "").substring(0, 12).toUpperCase();
            log.info("Cainiao mock-mode: envío simulado para pedido {} (platform={})", order.getOrderNumber(),
                    platformId);
            return new FulfillmentResult("Standard Shipping", "CN" + hex + "YQ", "LP" + hex, etaMax);
        }
        // TODO(real): POST a la Cainiao Open Platform (Global Logistics) con app-key/secret firmados.
        throw new UnsupportedOperationException("Cainiao real API no configurada todavía");
    }

    /** Un paso de la línea temporal de tracking. */
    public record TrackingStep(OrderStatus status, String description, String location, Instant occurredAt) {
    }

    /** Instantánea de tracking: estado actual del envío + pasos ocurridos hasta ahora. */
    public record TrackingSnapshot(OrderStatus currentStatus, List<TrackingStep> steps) {
    }

    /**
     * Consulta el tracking del envío. En mock simula el avance en función del tiempo transcurrido desde el
     * despacho ({@code forwardedAt}); cada etapa dura {@code mock-stage-minutes}. El estado actual del envío
     * se deriva de la etapa: registrado→FORWARDED, en tránsito/reparto→SHIPPED, entregado→DELIVERED.
     */
    public TrackingSnapshot track(String trackingNumber, Instant forwardedAt, String countryCode) {
        if (isActive()) {
            // TODO(real): GET tracking de Cainiao por trackingNumber y mapear sus estados a OrderStatus.
            throw new UnsupportedOperationException("Cainiao real API no configurada todavía");
        }
        Instant start = forwardedAt != null ? forwardedAt : Instant.now();
        long elapsedMin = Math.max(0, Duration.between(start, Instant.now()).toMinutes());
        long stageDur = Math.max(1, mockStageMinutes);
        int stage = (int) Math.min(5, elapsedMin / stageDur);
        String country = countryCode != null ? countryCode : "destino";

        String[][] plan = {
                { "FORWARDED", "Envío registrado", "Shenzhen, CN" },
                { "SHIPPED", "Recogido por el transportista", "Shenzhen, CN" },
                { "SHIPPED", "En tránsito internacional", "Hub internacional" },
                { "SHIPPED", "Llegó al país de destino", country },
                { "SHIPPED", "En reparto", "Centro de distribución local" },
                { "DELIVERED", "Entregado al destinatario", country },
        };
        List<TrackingStep> steps = new ArrayList<>();
        for (int i = 0; i <= stage; i++) {
            steps.add(new TrackingStep(OrderStatus.valueOf(plan[i][0]), plan[i][1], plan[i][2],
                    start.plus(Duration.ofMinutes((long) i * stageDur))));
        }
        OrderStatus current = OrderStatus.valueOf(plan[stage][0]);
        return new TrackingSnapshot(current, steps);
    }
}
