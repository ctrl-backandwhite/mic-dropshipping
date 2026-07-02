package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    private final CainiaoLinkClient linkClient;
    private final ObjectMapper objectMapper;

    // ── msg_type de las APIs del producto "Cainiao Cross-border E-commerce Export" ───────────
    // Nombres reales del catálogo de la app (appKey 682350). Los CAMPOS del payload/respuesta aún
    // dependen de la doc de cada API (TODO en realCreateShipment/realTrack); el msg_type ya es el real.
    private static final String MSG_CREATE_SHIPMENT = "CAINIAO_GLOBAL_TAKING_ORDER";   // crear envío (place order)
    private static final String MSG_GET_TRACE = "LOGISTICS_DETAIL_QUERY";              // consultar tracking (pull)

    @Value("${nexadrop.cainiao.enabled:false}")
    private boolean enabled;
    @Value("${nexadrop.cainiao.app-key:}")
    private String appKey;
    @Value("${nexadrop.cainiao.platform-id:nexadrop-dropshipping}")
    private String platformId;
    /** Código de recurso (资源码) de la app en Cainiao Open Platform → viaja en el campo {@code to_code}. */
    @Value("${nexadrop.cainiao.to-code:}")
    private String toCode;
    /** Cotizar el ENVÍO con Cainiao (con fallback a la tabla de zonas). false = tabla local (local). */
    @Value("${nexadrop.cainiao.shipping-quote-enabled:false}")
    private boolean shippingQuoteEnabled;
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
        // Coste de envío conmutable por entorno: en PRE (shipping-quote-enabled + Cainiao activo) se intenta
        // la tarifa de Cainiao; ante cualquier fallo/indisponibilidad se cae a la tabla de zonas (local).
        if (shippingQuoteEnabled && isActive()) {
            Integer cnCents = tryCainiaoShippingCents(zn, totalWeightGrams);
            if (cnCents != null) {
                return new ShippingQuote(true, zn.getCountryCode(), cnCents, "Cainiao", "Cainiao",
                        zn.getEtaMinDays(), zn.getEtaMaxDays(), zn.getZone());
            }
        }
        int amount = zn.getBaseCents() + (int) Math.round(zn.getPerKgCents() * kg);
        return new ShippingQuote(true, zn.getCountryCode(), amount, "Standard Shipping", "Standard Shipping",
                zn.getEtaMinDays(), zn.getEtaMaxDays(), zn.getZone());
    }

    /**
     * Tarifa de envío vía Cainiao (céntimos USD).
     *
     * <p><b>TODO(real):</b> cuando esté aprobada la app y conozcamos el msg_type + payload de la API de
     * cotización de tarifa (o venga en la respuesta de {@code CAINIAO_GLOBAL_TAKING_ORDER}), construir el
     * {@code logistics_interface} (país destino, peso, dimensiones) y parsear el importe → céntimos USD.
     * Devolver {@code null} ante error para caer a la tabla de zonas. De momento devuelve {@code null}.
     */
    private Integer tryCainiaoShippingCents(CainiaoZoneEntity zone, int totalWeightGrams) {
        return null; // placeholder honesto: sin API real (app pendiente), se usa la tabla de zonas.
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
        return realCreateShipment(order, etaMax);
    }

    /**
     * Llamada REAL al gateway Link para crear el envío. La fontanería (firma + POST) la hace
     * {@link CainiaoLinkClient}; aquí se construye el JSON de negocio y se parsea la respuesta.
     *
     * <p><b>TODO(real)</b>: ajustar {@code MSG_CREATE_SHIPMENT}, los campos del payload y las rutas de
     * parseo ({@code mailNo}/{@code lpCode}/…) al esquema EXACTO del producto que contrates. Tal cual,
     * envía un payload mínimo y lee los campos más habituales; si no aparecen, lanza un error con la
     * respuesta cruda para que puedas mapearla durante la integración.
     */
    private FulfillmentResult realCreateShipment(Order order, int etaMax) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("orderCode", order.getOrderNumber());
            body.put("countryCode", order.getShippingCountry());
            body.put("logisticProviderId", appKey);
            // TODO(real): añadir remitente/destinatario/items/peso según la API contratada.
            String resp = linkClient.invoke(MSG_CREATE_SHIPMENT, objectMapper.writeValueAsString(body), toCode);
            JsonNode r = objectMapper.readTree(resp);
            if (!r.path("success").asBoolean(true) && r.has("errorCode")) {
                throw new IllegalStateException("Cainiao createShipment rechazado: " + resp);
            }
            String tracking = firstText(r, "mailNo", "trackingNumber", "waybillCode");
            String ref = firstText(r, "lpCode", "fulfillmentOrderCode", "orderCode");
            String carrier = firstText(r, "cpName", "carrier");
            if (tracking == null) {
                throw new IllegalStateException("Cainiao createShipment sin nº de seguimiento; mapea la respuesta: " + resp);
            }
            log.info("Cainiao: envío real creado para pedido {} (tracking={})", order.getOrderNumber(), tracking);
            return new FulfillmentResult(carrier != null ? carrier : "Standard Shipping", tracking,
                    ref != null ? ref : tracking, etaMax);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cainiao createShipment: error de JSON", e);
        }
    }

    /** Primer campo no vacío de entre varios nombres candidatos (la respuesta varía según el producto). */
    private static String firstText(JsonNode node, String... keys) {
        for (String k : keys) {
            String v = node.path(k).asText(null);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
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
     * Tracking REAL: pide la traza al gateway y la mapea a la línea temporal interna.
     *
     * <p><b>TODO(real)</b>: ajustar {@code MSG_GET_TRACE}, el campo del array de eventos
     * ({@code traceDetailList}/…) y la traducción de cada código de acción de Cainiao a
     * {@link OrderStatus} (mapa {@code action -> status}). Tal cual, recorre el array más habitual y,
     * a falta de mapa, marca cada evento como {@code SHIPPED} y el último como {@code DELIVERED}.
     */
    private TrackingSnapshot realTrack(String trackingNumber, String countryCode) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("mailNo", trackingNumber);
            body.put("logisticProviderId", appKey);
            String resp = linkClient.invoke(MSG_GET_TRACE, objectMapper.writeValueAsString(body), toCode);
            JsonNode r = objectMapper.readTree(resp);
            JsonNode events = r.has("traceDetailList") ? r.get("traceDetailList")
                    : r.path("data").path("traceDetailList");
            List<TrackingStep> steps = new ArrayList<>();
            if (events.isArray()) {
                for (int i = 0; i < events.size(); i++) {
                    JsonNode ev = events.get(i);
                    boolean last = i == events.size() - 1;
                    // TODO(real): traducir ev.get("action") a OrderStatus con un mapa por código de Cainiao.
                    OrderStatus st = last ? OrderStatus.DELIVERED : OrderStatus.SHIPPED;
                    String desc = firstText(ev, "desc", "standerdDesc", "remark");
                    String loc = firstText(ev, "city", "location");
                    long ts = ev.path("time").asLong(0L);
                    Instant when = ts > 0 ? Instant.ofEpochMilli(ts) : Instant.now();
                    steps.add(new TrackingStep(st, desc != null ? desc : st.name(),
                            loc != null ? loc : (countryCode != null ? countryCode : "destino"), when));
                }
            }
            if (steps.isEmpty()) {
                // Aún sin eventos: el envío está registrado pero el transportista no lo ha escaneado.
                steps.add(new TrackingStep(OrderStatus.FORWARDED, "Envío registrado en Cainiao", "Shenzhen, CN",
                        Instant.now()));
            }
            OrderStatus current = steps.get(steps.size() - 1).status();
            return new TrackingSnapshot(current, steps);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cainiao track: error de JSON", e);
        }
    }
}
