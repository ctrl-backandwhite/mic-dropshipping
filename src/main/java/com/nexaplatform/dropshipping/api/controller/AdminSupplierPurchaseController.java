package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminSupplierPurchaseApi;
import com.nexaplatform.dropshipping.api.dto.in.AdminPurchaseBoughtDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminPurchasePackedDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminPurchaseShippedDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminSupplierPurchaseDtoOut;
import com.nexaplatform.dropshipping.application.service.PackOrderExportService;
import com.nexaplatform.dropshipping.application.service.PackOrderExportService.PackOrderPlan;
import com.nexaplatform.dropshipping.application.service.PurchaseEconomics;
import com.nexaplatform.dropshipping.application.service.SupplierPurchaseService;
import com.nexaplatform.dropshipping.application.service.SupplierPurchaseService.PurchaseView;
import com.nexaplatform.dropshipping.application.usecase.OrderUseCase;
import com.nexaplatform.dropshipping.domain.enums.PackServiceType;
import com.nexaplatform.dropshipping.domain.enums.PackWarehouse;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierPurchaseEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * La mesa de trabajo del admin para el tramo chino: qué comprar en 1688, en qué punto va cada bulto y
 * el fichero listo para subir al OMS de Yunfulfillment.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/purchases")
@RequiredArgsConstructor
public class AdminSupplierPurchaseController implements AdminSupplierPurchaseApi {

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    /** Los importes se compran y se pagan en yuanes; la comparación se enseña en su moneda. */
    private static final String CNY = "CNY";

    private final SupplierPurchaseService purchaseService;
    private final PackOrderExportService packOrderExportService;
    private final CurrencyRateService currencyRateService;
    private final OrderUseCase orderUseCase;

    /** Código de cliente en Yunfulfillment: va en el destinatario y pegado al final de la dirección. */
    @Value("${nexadrop.fulfillment.customer-code:CNHC459832}")
    private String customerCode;

    @Override
    public ResponseEntity<List<AdminSupplierPurchaseDtoOut>> queue() {
        return ResponseEntity.ok(toDtos(purchaseService.openQueueView()));
    }

    @Override
    public ResponseEntity<List<AdminSupplierPurchaseDtoOut>> forOrder(UUID orderId) {
        return ResponseEntity.ok(toDtos(purchaseService.viewsForOrder(orderId)));
    }

    @Override
    public ResponseEntity<List<AdminSupplierPurchaseDtoOut>> plan(UUID orderId) {
        return ResponseEntity.ok(toDtos(purchaseService.planForExistingOrder(orderId)));
    }

    @Override
    public ResponseEntity<List<AdminSupplierPurchaseDtoOut>> atRisk() {
        return ResponseEntity.ok(toDtos(purchaseService.atRiskViews()));
    }

    @Override
    public ResponseEntity<AdminSupplierPurchaseDtoOut> bought(UUID id, AdminPurchaseBoughtDtoIn body) {
        SupplierPurchaseEntity purchase = purchaseService.markPurchased(id, body.getPurchaseRef(),
                toCents(body.getCostCny()), toCents(body.getShippingCny()));
        forwardIfFullyPurchased(purchase.getOrderId());
        return ResponseEntity.ok(reload(purchase));
    }

    /**
     * Con toda la mercancía comprada, el pedido pasa a «enviado a proveedor» por su cuenta.
     *
     * <p>Ese salto era manual y en otra pantalla, y saltárselo tenía dos consecuencias que no parecían
     * relacionadas: el cliente seguía viendo «pagado» aunque su pedido estuviera comprado y de camino,
     * y el fichero de re-empaquetado no se activaba nunca, porque la guía internacional solo se emite
     * sobre pedidos despachados.
     *
     * <p>No adelanta la guía: esa tiene su propia condición —que TODOS los bultos vayan ya camino del
     * almacén—, así que comprar no basta para emitirla.
     *
     * <p>Un fallo aquí no puede tumbar el registro de la compra, que es lo que el admin acaba de hacer
     * y ya está guardado: se deja anotado y se sigue.
     */
    private void forwardIfFullyPurchased(UUID orderId) {
        if (orderId == null || !purchaseService.allPurchased(orderId)) {
            return;
        }
        try {
            orderUseCase.forwardOrder(orderId);
        } catch (RuntimeException e) {
            log.warn("No se pudo dar por enviado al proveedor el pedido {}: {}", orderId, e.getMessage());
        }
    }

    @Override
    public ResponseEntity<AdminSupplierPurchaseDtoOut> shipped(UUID id, AdminPurchaseShippedDtoIn body) {
        SupplierPurchaseEntity purchase = purchaseService.markShipped(id, body.getDomesticTracking(),
                body.getDomesticCarrier());
        // También aquí, y no solo al comprar: un pedido que se quedara atrás —porque sus compras se
        // registraron antes de que esto existiera— se pone al día en el siguiente avance en lugar de
        // quedarse colgado para siempre.
        forwardIfFullyPurchased(purchase.getOrderId());
        return ResponseEntity.ok(reload(purchase));
    }

    @Override
    public ResponseEntity<AdminSupplierPurchaseDtoOut> received(UUID id) {
        SupplierPurchaseEntity purchase = purchaseService.markReceived(id);
        forwardIfFullyPurchased(purchase.getOrderId());
        return ResponseEntity.ok(reload(purchase));
    }

    @Override
    public ResponseEntity<AdminSupplierPurchaseDtoOut> packed(UUID id, AdminPurchasePackedDtoIn body) {
        return ResponseEntity.ok(reload(purchaseService.markPacked(id, body.getPackOrderNo(), body.getServiceType())));
    }

    @Override
    public ResponseEntity<AdminSupplierPurchaseDtoOut> cancel(UUID id, String reason) {
        return ResponseEntity.ok(reload(purchaseService.cancel(id, reason)));
    }

    @Override
    public ResponseEntity<Map<String, Object>> packSheetPreview() {
        PackOrderPlan plan = packOrderExportService.plan();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exportable", plan.rows().size());
        out.put("orderIds", plan.orderIds());
        out.put("rows", plan.rows());
        out.put("issues", plan.issues());
        return ResponseEntity.ok(out);
    }

    @Override
    public ResponseEntity<byte[]> packSheet() {
        PackOrderPlan plan = packOrderExportService.plan();
        byte[] xls = packOrderExportService.toXls(plan.rows());
        // Marcadas como exportadas: salen de la cola de exportación para no repetir el YT en el
        // siguiente fichero. NO es marcar reempaquetado (eso es confirmPacked, tras subir al OMS).
        purchaseService.markExported(plan.orderIds());
        // Nombre con fecha y hora para distinguir descargas y saber cuál es la última. El front pone su
        // propia hora (la del navegador); este es el respaldo para quien llame la API directamente.
        String filename = "yunfulfillment-packorder_" + FILE_STAMP.format(Instant.now().atZone(ZoneId.systemDefault()))
                + ".xls";
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM).body(xls);
    }

    @Override
    public ResponseEntity<AdminSupplierPurchaseDtoOut> reexport(UUID id) {
        return ResponseEntity.ok(reload(purchaseService.requestReexport(id)));
    }

    /**
     * Cierra el ciclo tras subir el fichero al OMS.
     *
     * <p>No se marca al descargar: entre la descarga y la subida puede pasar cualquier cosa, y dar por
     * reempaquetado un bulto cuyo fichero nunca se subió es exactamente lo que lleva a los 30 días y la
     * destrucción.
     */
    @Override
    public ResponseEntity<Map<String, Object>> confirmPacked(List<UUID> purchaseIds) {
        int ok = 0;
        List<String> failed = new ArrayList<>();
        for (UUID id : purchaseIds) {
            try {
                purchaseService.markPacked(id, null, null);
                ok++;
            } catch (RuntimeException e) {
                failed.add(id.toString());
                log.warn("No se pudo marcar como reempaquetada la compra {}: {}", id, e.getMessage());
            }
        }
        Map<String, Object> out = new HashMap<>();
        out.put("marked", ok);
        out.put("failed", failed);
        return ResponseEntity.ok(out);
    }

    private List<AdminSupplierPurchaseDtoOut> toDtos(List<PurchaseView> views) {
        return views.stream().map(this::toDto).toList();
    }

    /** Proyección tras una acción suelta: recarga la vista para devolver la fila completa, no un trozo. */
    private AdminSupplierPurchaseDtoOut reload(SupplierPurchaseEntity p) {
        return purchaseService.viewsForOrder(p.getOrderId()).stream()
                .filter(v -> v.purchase().getId().equals(p.getId())).findFirst().map(this::toDto)
                .orElseGet(() -> AdminSupplierPurchaseDtoOut.builder().id(p.getId()).orderId(p.getOrderId())
                        .status(p.getStatus().name()).build());
    }

    /**
     * Proyecta la compra con todo lo que hace falta para comprarla sin salir de la pantalla.
     *
     * <p>Las líneas llegan ya resueltas desde el servicio: {@code OrderItem} viene enriquecido por el
     * adaptador con el título chino, la variante y la URL de la ficha de 1688, pero es una colección
     * perezosa que hay que leer dentro de la transacción.
     */
    private AdminSupplierPurchaseDtoOut toDto(PurchaseView view) {
        SupplierPurchaseEntity p = view.purchase();
        List<AdminSupplierPurchaseDtoOut.Line> lines = new ArrayList<>();
        for (int i = 0; i < view.lines().size(); i++) {
            OrderItem item = view.lines().get(i);
            lines.add(AdminSupplierPurchaseDtoOut.Line.builder().orderItemId(item.getId())
                    .title(item.getTitleSnapshot()).titleZh(item.getProductTitleZh()).variantName(item.getVariantName())
                    .imageUrl(item.getVariantImageUrl() != null ? item.getVariantImageUrl() : item.getProductImageUrl())
                    .sourceUrl(item.getProductSourceUrl()).quantity(view.quantities().get(i)).build());
        }
        PackWarehouse warehouse = PackWarehouse.fromCode(p.getWarehouseCode());
        int parcels = Math.max(1, view.parcelsInOrder());
        String supplierName = view.supplierName();
        // Lo que el catálogo decía frente a lo que costó. Sin esto, el coste y el envío que el admin
        // teclea al comprar se guardaban y no los leía nadie.
        PurchaseEconomics economics = PurchaseEconomics.of(view.lines(), view.quantities(), p.getCostCnyCents(),
                p.getShippingCnyCents());
        String currency = view.orderCurrency();
        return AdminSupplierPurchaseDtoOut.builder().id(p.getId()).orderId(p.getOrderId())
                .orderNumber(view.orderNumber()).status(p.getStatus().name()).supplierId(p.getSupplierId())
                .supplierName(supplierName).warehouseCode(p.getWarehouseCode())
                .warehouseAddress(warehouse.fullAddress(customerCode)).purchaseRef(p.getPurchaseRef())
                .costCny(fromCents(p.getCostCnyCents())).shippingCny(fromCents(p.getShippingCnyCents()))
                .expectedCostCnyFormatted(cny(economics.expectedCostCnyCents()))
                .realCostCnyFormatted(cny(economics.realCostCnyCents()))
                .costVarianceCnyFormatted(signed(economics.varianceCnyCents(), CNY)).overBudget(economics.overBudget())
                .expectedMarginFormatted(money(economics.expectedMarginCents(), currency))
                .realMarginFormatted(money(economics.realMarginCents(), currency))
                .realMarginPct(economics.realMarginPct()).purchasedAt(p.getPurchasedAt())
                .domesticTracking(p.getDomesticTracking()).domesticCarrier(p.getDomesticCarrier())
                .shippedAt(p.getShippedAt()).receivedAt(p.getReceivedAt()).packOrderNo(p.getPackOrderNo())
                .packServiceType(p.getPackServiceType()).packSubmittedAt(p.getPackSubmittedAt())
                .exportedAt(p.getExportedAt()).daysInWarehouse(daysInWarehouse(p.getReceivedAt()))
                .suggestedServiceType(PackServiceType.forIncomingParcels(parcels).name()).notes(p.getNotes())
                .createdAt(p.getCreatedAt()).items(lines).build();
    }

    /** Días que el bulto lleva en el almacén; a los 30 se destruye sin compensación. */
    private static Integer daysInWarehouse(Instant receivedAt) {
        return receivedAt == null ? null : (int) Duration.between(receivedAt, Instant.now()).toDays();
    }

    /** Importe en la divisa del pedido; null si no hay cantidad o no se sabe en qué moneda se cobró. */
    private String money(Long cents, String currency) {
        if (cents == null || currency == null) {
            return null;
        }
        return currencyRateService.formatDisplay(fromCents(cents), currency);
    }

    private String cny(Long cents) {
        return money(cents, CNY);
    }

    /**
     * Como {@link #money}, pero deja ver el signo del desvío.
     *
     * <p>El formato de moneda escribe los negativos con paréntesis o con el signo pegado según el
     * idioma, y aquí lo que importa de un vistazo es si se pagó de más o de menos.
     */
    private String signed(Long cents, String currency) {
        if (cents == null) {
            return null;
        }
        String texto = money(Math.abs(cents), currency);
        return texto == null ? null : (cents < 0 ? "−" : "+") + texto;
    }

    /** El dinero se guarda en céntimos para no arrastrar errores de coma flotante. */
    private static Long toCents(BigDecimal amount) {
        return amount == null ? null : amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static BigDecimal fromCents(Long cents) {
        return cents == null ? null : BigDecimal.valueOf(cents).movePointLeft(2);
    }
}
