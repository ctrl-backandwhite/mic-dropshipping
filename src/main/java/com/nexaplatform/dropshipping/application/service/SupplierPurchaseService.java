package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.enums.SupplierPurchaseStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierPurchaseEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierPurchaseItemEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupplierPurchaseItemRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupplierPurchaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * El tramo que va del cobro al cliente hasta que la mercancía sale del almacén chino.
 *
 * <p>Antes de esto el sistema saltaba directamente de «pagado» a «guía internacional creada», y eso
 * daba por supuesto que el producto ya existía en algún sitio. No existe: hay que comprarlo en 1688,
 * el proveedor lo manda al almacén de Dongguan y allí lo reempaquetan. Este servicio registra esos
 * hitos y, sobre todo, impide que se genere una guía —que se paga y arranca el reloj del seguimiento—
 * para mercancía que todavía nadie ha comprado.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierPurchaseService {

    /** Estados en los que el bulto aún no ha salido hacia el almacén. */
    private static final Set<SupplierPurchaseStatus> NOT_ON_THE_MOVE =
            EnumSet.of(SupplierPurchaseStatus.PENDING, SupplierPurchaseStatus.PURCHASED);

    /** Lo que el admin tiene por delante: comprar, esperar al proveedor, o registrar el empaquetado. */
    private static final Set<SupplierPurchaseStatus> OPEN =
            EnumSet.of(SupplierPurchaseStatus.PENDING, SupplierPurchaseStatus.PURCHASED,
                    SupplierPurchaseStatus.IN_TRANSIT, SupplierPurchaseStatus.AT_WAREHOUSE);

    /**
     * El almacén destruye sin compensación un bulto que lleve 30 días sin instrucciones. Se avisa a los
     * 20 para que quede margen de reacción: el aviso a los 29 no sirve de nada.
     */
    private static final Duration DESTRUCTION_WARNING = Duration.ofDays(20);

    private final SupplierPurchaseRepository purchaseRepository;
    private final SupplierPurchaseItemRepository itemRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;

    @Value("${nexadrop.fulfillment.warehouse-code:CNCHASHAN}")
    private String defaultWarehouseCode;

    /**
     * Al cobrar el pedido, deja anotado qué hay que comprar y a quién.
     *
     * <p>Se agrupa por proveedor porque esa es la unidad física: el proveedor manda un paquete con todo
     * lo que se le compre para el pedido, y el seguimiento nacional es de ese paquete. Es idempotente —
     * un webhook de pago duplicado no debe duplicar la lista de la compra.
     */
    @Transactional
    public void planPurchases(Order order) {
        if (order == null || order.getId() == null || purchaseRepository.existsByOrderId(order.getId())) {
            return;
        }
        Map<UUID, List<OrderItem>> bySupplier = groupBySupplier(order.getItems());
        for (Map.Entry<UUID, List<OrderItem>> e : bySupplier.entrySet()) {
            SupplierPurchaseEntity purchase = purchaseRepository.save(SupplierPurchaseEntity.builder()
                    .orderId(order.getId())
                    .supplierId(e.getKey())
                    .status(SupplierPurchaseStatus.PENDING)
                    .warehouseCode(defaultWarehouseCode)
                    .createdAt(Instant.now())
                    .build());
            for (OrderItem item : e.getValue()) {
                itemRepository.save(SupplierPurchaseItemEntity.builder()
                        .purchaseId(purchase.getId())
                        .orderItemId(item.getId())
                        .quantity(item.getQuantity())
                        .build());
            }
        }
        log.info("Compras planificadas para el pedido {}: {} proveedor(es)",
                order.getOrderNumber(), bySupplier.size());
    }

    /**
     * Reparte las líneas por proveedor conservando el orden de aparición, para que la cola de compras se
     * lea igual que el pedido.
     *
     * <p>Una línea sin proveedor resoluble se deja fuera en vez de agruparla bajo un id nulo: mezclarla
     * con otra sin proveedor fabricaría una compra a nadie, y es mejor que falte y se vea.
     */
    private Map<UUID, List<OrderItem>> groupBySupplier(List<OrderItem> items) {
        Map<UUID, List<OrderItem>> bySupplier = new LinkedHashMap<>();
        if (items == null) {
            return bySupplier;
        }
        for (OrderItem item : items) {
            UUID supplierId = supplierIdOf(item.getProductId());
            if (supplierId == null) {
                log.warn("Línea {} sin proveedor resoluble: no entra en la cola de compras", item.getId());
                continue;
            }
            bySupplier.computeIfAbsent(supplierId, k -> new ArrayList<>()).add(item);
        }
        return bySupplier;
    }

    /**
     * El proveedor del producto.
     *
     * <p>{@code getSupplier().getId()} sobre la relación perezosa NO dispara consulta: Hibernate tiene
     * el identificador en el proxy. Por eso no hace falta una proyección aparte.
     */
    private UUID supplierIdOf(UUID productId) {
        if (productId == null) {
            return null;
        }
        return productRepository.findById(productId)
                .map(ProductEntity::getSupplier)
                .map(s -> s.getId())
                .orElse(null);
    }

    /** La cola de trabajo: todo lo que aún no ha salido del almacén. */
    @Transactional(readOnly = true)
    public List<SupplierPurchaseEntity> openQueue() {
        return purchaseRepository.findByStatusInOrderByCreatedAtAsc(OPEN);
    }

    /**
     * Una compra con todo lo que hace falta para pintarla: el pedido al que pertenece y sus líneas.
     *
     * <p>Existe porque las líneas del pedido son una colección perezosa: resolverlas en el controlador
     * revienta con {@code LazyInitializationException} al no haber ya sesión de Hibernate. La lectura
     * tiene que ocurrir dentro de la transacción, y aquí es donde está.
     */
    public record PurchaseView(SupplierPurchaseEntity purchase, String orderNumber, String orderTracking,
                               int parcelsInOrder, String supplierName, List<OrderItem> lines,
                               List<Integer> quantities) {
    }

    @Transactional(readOnly = true)
    public List<PurchaseView> openQueueView() {
        return toViews(openQueue());
    }

    @Transactional(readOnly = true)
    public List<PurchaseView> viewsForOrder(UUID orderId) {
        return toViews(purchaseRepository.findByOrderId(orderId));
    }

    /**
     * Genera las compras de un pedido ya pagado que no las tiene.
     *
     * <p>Los pedidos cobrados antes de esta funcionalidad se quedaron sin lista de la compra, y sin ella
     * no aparecen en la cola ni se pueden reempaquetar. Se apoya en {@link #planPurchases}, que es
     * idempotente, así que llamarlo sobre un pedido que ya las tiene no duplica nada.
     */
    @Transactional
    public List<PurchaseView> planForExistingOrder(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("No existe el pedido " + orderId));
        planPurchases(order);
        return toViews(purchaseRepository.findByOrderId(orderId));
    }

    @Transactional(readOnly = true)
    public List<PurchaseView> atRiskViews() {
        return toViews(atRiskOfDestruction());
    }

    private List<PurchaseView> toViews(List<SupplierPurchaseEntity> purchases) {
        List<PurchaseView> out = new ArrayList<>();
        for (SupplierPurchaseEntity p : purchases) {
            Order order = orderRepository.findById(p.getOrderId()).orElse(null);
            Map<UUID, OrderItem> byId = new LinkedHashMap<>();
            if (order != null && order.getItems() != null) {
                order.getItems().forEach(i -> byId.put(i.getId(), i));
            }
            List<OrderItem> lines = new ArrayList<>();
            List<Integer> quantities = new ArrayList<>();
            String supplierName = null;
            for (SupplierPurchaseItemEntity link : itemRepository.findByPurchaseId(p.getId())) {
                OrderItem item = byId.get(link.getOrderItemId());
                if (item == null) {
                    continue;
                }
                supplierName = supplierName != null ? supplierName : item.getSupplierName();
                lines.add(item);
                quantities.add(link.getQuantity());
            }
            int parcels = purchaseRepository.findByOrderId(p.getOrderId()).size();
            out.add(new PurchaseView(p, order != null ? order.getOrderNumber() : null,
                    order != null ? order.getTrackingNumber() : null, parcels, supplierName,
                    lines, quantities));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<SupplierPurchaseEntity> forOrder(UUID orderId) {
        return purchaseRepository.findByOrderId(orderId);
    }

    @Transactional(readOnly = true)
    public List<SupplierPurchaseItemEntity> itemsOf(UUID purchaseId) {
        return itemRepository.findByPurchaseId(purchaseId);
    }

    /** Comprado y pagado en 1688. El coste real es lo que convierte el margen en un dato y no una estimación. */
    @Transactional
    public SupplierPurchaseEntity markPurchased(UUID id, String purchaseRef, Long costCnyCents,
                                                Long shippingCnyCents) {
        SupplierPurchaseEntity p = require(id);
        p.setPurchaseRef(purchaseRef);
        p.setCostCnyCents(costCnyCents);
        p.setShippingCnyCents(shippingCnyCents);
        p.setPurchasedAt(Instant.now());
        p.setStatus(SupplierPurchaseStatus.PURCHASED);
        return save(p);
    }

    /**
     * El proveedor lo ha despachado: ya hay seguimiento nacional.
     *
     * <p>Este es el hito que habilita la guía internacional. El número no se normaliza a mayúsculas ni se
     * recorta más allá de los espacios: el almacén empareja por coincidencia exacta.
     */
    @Transactional
    public SupplierPurchaseEntity markShipped(UUID id, String domesticTracking, String carrier) {
        SupplierPurchaseEntity p = require(id);
        p.setDomesticTracking(domesticTracking == null ? null : domesticTracking.trim());
        p.setDomesticCarrier(carrier);
        p.setShippedAt(Instant.now());
        p.setStatus(SupplierPurchaseStatus.IN_TRANSIT);
        return save(p);
    }

    /** El almacén ha recibido el bulto. Desde aquí corren los 30 días hasta la destrucción. */
    @Transactional
    public SupplierPurchaseEntity markReceived(UUID id) {
        SupplierPurchaseEntity p = require(id);
        p.setReceivedAt(Instant.now());
        p.setStatus(SupplierPurchaseStatus.AT_WAREHOUSE);
        return save(p);
    }

    /** Orden de re-empaquetado dada de alta en Yunfulfillment: el bulto ya tiene instrucciones. */
    @Transactional
    public SupplierPurchaseEntity markPacked(UUID id, String packOrderNo, String serviceType) {
        SupplierPurchaseEntity p = require(id);
        p.setPackOrderNo(packOrderNo);
        p.setPackServiceType(serviceType);
        p.setPackSubmittedAt(Instant.now());
        p.setStatus(SupplierPurchaseStatus.PACKED);
        return save(p);
    }

    @Transactional
    public SupplierPurchaseEntity cancel(UUID id, String reason) {
        SupplierPurchaseEntity p = require(id);
        p.setStatus(SupplierPurchaseStatus.CANCELLED);
        p.setNotes(reason);
        return save(p);
    }

    /**
     * ¿Se puede ya crear la guía internacional de este pedido?
     *
     * <p>Solo cuando TODOS sus bultos van camino del almacén. Un pedido de dos proveedores donde uno ha
     * enviado y el otro ni se ha comprado no tiene mercancía completa: emitir la guía ahí significa
     * pagarla y arrancar el reloj del seguimiento del cliente sobre un paquete a medias.
     *
     * <p>Los pedidos anteriores a esta funcionalidad no tienen compras registradas. Para ellos se
     * responde que sí, porque su mercancía se gestionó fuera del sistema y bloquearlos ahora
     * congelaría envíos que ya estaban en marcha.
     */
    @Transactional(readOnly = true)
    public boolean readyForInternationalShipment(UUID orderId) {
        if (!purchaseRepository.existsByOrderId(orderId)) {
            return true;
        }
        return !purchaseRepository.existsByOrderIdAndStatusIn(orderId, NOT_ON_THE_MOVE);
    }

    /**
     * Bultos que llevan demasiado tiempo en el almacén sin orden de re-empaquetado.
     *
     * <p>Es la única pérdida irreversible de todo el flujo: a los 30 días el almacén los destruye y el
     * contrato excluye cualquier compensación.
     */
    @Transactional(readOnly = true)
    public List<SupplierPurchaseEntity> atRiskOfDestruction() {
        Instant limit = Instant.now().minus(DESTRUCTION_WARNING);
        return purchaseRepository.findByStatusInOrderByCreatedAtAsc(
                        EnumSet.of(SupplierPurchaseStatus.AT_WAREHOUSE)).stream()
                .filter(p -> p.getReceivedAt() != null && p.getReceivedAt().isBefore(limit))
                .toList();
    }

    private SupplierPurchaseEntity save(SupplierPurchaseEntity p) {
        p.setUpdatedAt(Instant.now());
        return purchaseRepository.save(p);
    }

    /**
     * La compra, o un 404 con un motivo que se entienda.
     *
     * <p>Antes lanzaba {@code IllegalArgumentException}, que el manejador global traduce a un 400
     * «Parámetros de la petición inválidos» — y no es eso: los parámetros están bien, lo que falta es la
     * compra. Pasa en cuanto la pantalla lleva un rato abierta y la fila ya no existe.
     */
    private SupplierPurchaseEntity require(UUID id) {
        Optional<SupplierPurchaseEntity> found = purchaseRepository.findById(id);
        return found.orElseThrow(() -> new NotFoundException(
                "La compra ya no existe. Puede que la hayan cancelado desde otra pantalla; recarga la página."));
    }
}
