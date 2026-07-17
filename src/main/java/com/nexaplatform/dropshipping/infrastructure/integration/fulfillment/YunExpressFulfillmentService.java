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
 * Proveedor de fulfillment con <b>YunExpress</b> (云途) — carrier ACTIVO del sistema.
 *
 * <p>Implementa {@link FulfillmentProvider} y es la única implementación cableada (Cainiao queda fuera del
 * flujo). Igual que estaba Cainiao, funciona <b>mock-first</b>: con {@code nexadrop.yunexpress.enabled=false}
 * (por defecto) o sin credenciales, genera datos deterministas — cobertura/tarifa desde la tabla de zonas y
 * un tracking simulado que avanza con el tiempo — para que TODO el flujo (despacho → en camino → entregado)
 * funcione sin API real. Con credenciales + firma reales, las ramas {@code real*} llamarán a la YunExpress
 * Open Platform vía {@link YunExpressClient}.
 *
 * <p><b>Impuestos:</b> YunExpress usa canales <b>DDP</b> (aranceles/IVA incluidos, los paga el comercio) o
 * <b>DDU</b> (los paga el destinatario), y <b>IOSS</b> para el IVA de la UE. El modo por defecto y el IOSS se
 * configuran en {@code nexadrop.yunexpress.*} y se inyectarán en el payload de creación del envío.
 *
 * <p>Nota de cobertura: se reutiliza la tabla {@code cainiao_shipping_zone} (datos genéricos de país: base,
 * por-kg, ETA) como fuente de zonas mientras no exista una tabla propia de canales de YunExpress.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class YunExpressFulfillmentService implements FulfillmentProvider {

    /** Nombre de cara al cliente (sin exponer marca del carrier), igual que hacía Cainiao. */
    private static final String CARRIER_NAME = "Standard Shipping";

    private final CainiaoZoneRepository zoneRepository;
    private final YunExpressClient client;

    @Value("${nexadrop.yunexpress.enabled:false}")
    private boolean enabled;
    /** DDP = impuestos incluidos (los paga el comercio); DDU = los paga el destinatario en destino. */
    @Value("${nexadrop.yunexpress.default-tax-mode:DDP}")
    private String defaultTaxMode;
    /** Número IOSS del comercio para despacho de IVA en la UE (pedidos <=150 EUR). Vacío = sin IOSS. */
    @Value("${nexadrop.yunexpress.ioss-number:}")
    private String iossNumber;
    /** Minutos por etapa del tracking simulado (mock) — pon un valor pequeño para ver el avance en demo. */
    @Value("${nexadrop.yunexpress.mock-stage-minutes:2}")
    private long mockStageMinutes;

    /** Modo de despacho fiscal del canal. */
    public enum TaxMode { DDP, DDU }

    private boolean isActive() {
        return enabled && client.hasCredentials();
    }

    // ── Cobertura ────────────────────────────────────────────────────────────────────────────────

    @Override
    public boolean isSupported(String countryCode) {
        return zone(countryCode).isPresent();
    }

    @Override
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

    // ── Cotización ───────────────────────────────────────────────────────────────────────────────

    @Override
    public ShippingQuote quote(String countryCode, int totalWeightGrams) {
        Optional<CainiaoZoneEntity> z = zone(countryCode);
        if (z.isEmpty()) {
            return ShippingQuote.unsupported(countryCode);
        }
        CainiaoZoneEntity zn = z.get();
        double kg = Math.max(0.1, totalWeightGrams / 1000.0);
        if (isActive()) {
            Integer cents = tryRealShippingCents(zn, totalWeightGrams);
            if (cents != null) {
                return new ShippingQuote(true, zn.getCountryCode(), cents, CARRIER_NAME, CARRIER_NAME,
                        zn.getEtaMinDays(), zn.getEtaMaxDays(), zn.getZone());
            }
        }
        int amount = zn.getBaseCents() + (int) Math.round(zn.getPerKgCents() * kg);
        return new ShippingQuote(true, zn.getCountryCode(), amount, CARRIER_NAME, CARRIER_NAME,
                zn.getEtaMinDays(), zn.getEtaMaxDays(), zn.getZone());
    }

    /**
     * Tarifa real vía YunExpress (céntimos USD).
     * <p><b>TODO(real):</b> llamar al endpoint de tarifa/canales con país + peso + dimensiones, elegir canal
     * (DDP/DDU según {@link #resolveTaxMode}) y parsear el importe → céntimos USD. Devolver {@code null} ante
     * error para caer a la tabla de zonas. De momento devuelve {@code null}.
     */
    private Integer tryRealShippingCents(CainiaoZoneEntity zone, int totalWeightGrams) {
        return null;
    }

    // ── Crear envío ──────────────────────────────────────────────────────────────────────────────

    @Override
    public FulfillmentResult createShipment(Order order) {
        int etaMax = zone(order.getShippingCountry()).map(CainiaoZoneEntity::getEtaMaxDays).orElse(20);
        if (!isActive()) {
            String hex = order.getId().toString().replace("-", "").substring(0, 12).toUpperCase();
            log.info("YunExpress mock-mode: envío simulado para pedido {}", order.getOrderNumber());
            return new FulfillmentResult(CARRIER_NAME, "YT" + hex + "YE", "YE" + hex, etaMax);
        }
        return realCreateShipment(order, etaMax);
    }

    /**
     * Creación REAL del envío en YunExpress.
     * <p><b>TODO(real):</b> construir el payload (remitente, destinatario, ítems, peso/dims, canal,
     * {@link #resolveTaxMode}, IOSS via {@link #iossNumberOrNull}) y llamar a
     * {@code client.invoke("/api/WayBill/CreateOrder", json)}; parsear tracking + label. Ver
     * open.yunexpress.cn/openApi/doc.
     */
    private FulfillmentResult realCreateShipment(Order order, int etaMax) {
        throw new UnsupportedOperationException(
                "YunExpress createShipment real pendiente: implementar payload/endpoint y firma. "
                        + "Mientras, mantener enabled=false para usar el modo mock.");
    }

    // ── Tracking ─────────────────────────────────────────────────────────────────────────────────

    @Override
    public TrackingSnapshot track(String trackingNumber, Instant forwardedAt, String countryCode) {
        if (isActive()) {
            return realTrack(trackingNumber, countryCode);
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

    /**
     * Tracking REAL vía YunExpress.
     * <p><b>TODO(real):</b> llamar al endpoint de tracking (o recibir el push por eventManager) y mapear los
     * códigos de estado de YunExpress a {@link OrderStatus}.
     */
    private TrackingSnapshot realTrack(String trackingNumber, String countryCode) {
        throw new UnsupportedOperationException(
                "YunExpress track real pendiente: implementar endpoint de tracking / callback eventManager.");
    }

    // ── Impuestos (DDP/DDU + IOSS) ───────────────────────────────────────────────────────────────

    /**
     * Modo de despacho fiscal (DDP/DDU) para un país destino. Hoy devuelve el modo por defecto configurado;
     * en el futuro se podrá sobreescribir por país según el canal disponible.
     */
    public TaxMode resolveTaxMode(String countryCode) {
        try {
            return TaxMode.valueOf(defaultTaxMode.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return TaxMode.DDP;
        }
    }

    /** Número IOSS configurado del comercio (o null), para despacho de IVA en la UE. */
    public String iossNumberOrNull() {
        return iossNumber != null && !iossNumber.isBlank() ? iossNumber.trim() : null;
    }
}
