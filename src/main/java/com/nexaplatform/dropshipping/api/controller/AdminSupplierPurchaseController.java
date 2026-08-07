package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminSupplierPurchaseApi;
import com.nexaplatform.dropshipping.api.dto.in.AdminPurchaseBoughtDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminPurchasePackedDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminPurchaseShippedDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminSupplierPurchaseDtoOut;
import com.nexaplatform.dropshipping.application.service.PackOrderExportService;
import com.nexaplatform.dropshipping.application.service.PackOrderExportService.PackOrderPlan;
import com.nexaplatform.dropshipping.application.service.SupplierPurchaseService;
import com.nexaplatform.dropshipping.application.service.SupplierPurchaseService.PurchaseView;
import com.nexaplatform.dropshipping.domain.enums.PackServiceType;
import com.nexaplatform.dropshipping.domain.enums.PackWarehouse;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
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

    private static final String FILENAME = "yunfulfillment-packorder.xls";

    private final SupplierPurchaseService purchaseService;
    private final PackOrderExportService packOrderExportService;

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
    public ResponseEntity<List<AdminSupplierPurchaseDtoOut>> atRisk() {
        return ResponseEntity.ok(toDtos(purchaseService.atRiskViews()));
    }

    @Override
    public ResponseEntity<AdminSupplierPurchaseDtoOut> bought(UUID id, AdminPurchaseBoughtDtoIn body) {
        return ResponseEntity.ok(reload(purchaseService.markPurchased(id, body.getPurchaseRef(),
                toCents(body.getCostCny()), toCents(body.getShippingCny()))));
    }

    @Override
    public ResponseEntity<AdminSupplierPurchaseDtoOut> shipped(UUID id, AdminPurchaseShippedDtoIn body) {
        return ResponseEntity.ok(reload(purchaseService.markShipped(id, body.getDomesticTracking(),
                body.getDomesticCarrier())));
    }

    @Override
    public ResponseEntity<AdminSupplierPurchaseDtoOut> received(UUID id) {
        return ResponseEntity.ok(reload(purchaseService.markReceived(id)));
    }

    @Override
    public ResponseEntity<AdminSupplierPurchaseDtoOut> packed(UUID id, AdminPurchasePackedDtoIn body) {
        return ResponseEntity.ok(reload(purchaseService.markPacked(id, body.getPackOrderNo(),
                body.getServiceType())));
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
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + FILENAME + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(xls);
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
                .filter(v -> v.purchase().getId().equals(p.getId()))
                .findFirst().map(this::toDto)
                .orElseGet(() -> AdminSupplierPurchaseDtoOut.builder().id(p.getId())
                        .orderId(p.getOrderId()).status(p.getStatus().name()).build());
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
            lines.add(AdminSupplierPurchaseDtoOut.Line.builder()
                    .orderItemId(item.getId())
                    .title(item.getTitleSnapshot())
                    .titleZh(item.getProductTitleZh())
                    .variantName(item.getVariantName())
                    .imageUrl(item.getVariantImageUrl() != null ? item.getVariantImageUrl()
                            : item.getProductImageUrl())
                    .sourceUrl(item.getProductSourceUrl())
                    .quantity(view.quantities().get(i))
                    .build());
        }
        PackWarehouse warehouse = PackWarehouse.fromCode(p.getWarehouseCode());
        int parcels = Math.max(1, view.parcelsInOrder());
        String supplierName = view.supplierName();
        return AdminSupplierPurchaseDtoOut.builder()
                .id(p.getId())
                .orderId(p.getOrderId())
                .orderNumber(view.orderNumber())
                .status(p.getStatus().name())
                .supplierId(p.getSupplierId())
                .supplierName(supplierName)
                .warehouseCode(p.getWarehouseCode())
                .warehouseAddress(warehouse.fullAddress(customerCode))
                .purchaseRef(p.getPurchaseRef())
                .costCny(fromCents(p.getCostCnyCents()))
                .shippingCny(fromCents(p.getShippingCnyCents()))
                .purchasedAt(p.getPurchasedAt())
                .domesticTracking(p.getDomesticTracking())
                .domesticCarrier(p.getDomesticCarrier())
                .shippedAt(p.getShippedAt())
                .receivedAt(p.getReceivedAt())
                .packOrderNo(p.getPackOrderNo())
                .packServiceType(p.getPackServiceType())
                .packSubmittedAt(p.getPackSubmittedAt())
                .daysInWarehouse(daysInWarehouse(p.getReceivedAt()))
                .suggestedServiceType(PackServiceType.forIncomingParcels(parcels).name())
                .notes(p.getNotes())
                .createdAt(p.getCreatedAt())
                .items(lines)
                .build();
    }

    /** Días que el bulto lleva en el almacén; a los 30 se destruye sin compensación. */
    private static Integer daysInWarehouse(Instant receivedAt) {
        return receivedAt == null ? null : (int) Duration.between(receivedAt, Instant.now()).toDays();
    }

    /** El dinero se guarda en céntimos para no arrastrar errores de coma flotante. */
    private static Long toCents(BigDecimal amount) {
        return amount == null ? null : amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private static BigDecimal fromCents(Long cents) {
        return cents == null ? null : BigDecimal.valueOf(cents).movePointLeft(2);
    }
}
