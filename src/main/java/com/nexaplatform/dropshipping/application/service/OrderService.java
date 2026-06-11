package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.AddressInput;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.CreateOrderRequest;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.OrderItemInput;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.OrderItemView;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.OrderView;
import com.nexaplatform.dropshipping.api.dto.out.AdminOrderAddressDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminOrderDetailDtoOut;
import com.nexaplatform.dropshipping.api.dto.in.MeCheckoutDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminOrderRowDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeOrderDetailDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeOrderRowDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.PartnerOrderDtoOut;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.AdminOrderMapper;
import com.nexaplatform.dropshipping.api.mapper.PartnerOrderDtoMapper;
import com.nexaplatform.dropshipping.application.notifications.NotificationsPublisher;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AddressEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerOrderEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OrderItemEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserAddressEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.AddressRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ShopConnectionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserAddressRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final SecureRandom RNG = new SecureRandom();

    private final OrderRepository orderRepository;
    private final AddressRepository addressRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final UserRepository userRepository;
    private final ShopConnectionRepository shopConnectionRepository;
    private final UserAddressRepository userAddressRepository;
    private final AdminOrderMapper adminOrderMapper;
    private final PartnerOrderDtoMapper partnerOrderDtoMapper;
    private final WebhookDispatcherService webhooks;
    private final WalletService walletService;
    private final NotificationsPublisher notificationsPublisher;

    @Value("${nexadrop.demo.orders-enabled:false}")
    private boolean demoOrdersEnabled;

    @Transactional
    public OrderView createOrder(UUID partnerAppId, UUID userId, CreateOrderRequest req) {
        if (req.items() == null || req.items().isEmpty()) {
            throw new BusinessException("Order must have at least one item");
        }

        AddressEntity shipping = addressRepository.save(toAddress(req.shippingAddress()));
        AddressEntity billing = req.billingAddress() != null ? addressRepository.save(toAddress(req.billingAddress())) : null;

        CustomerOrderEntity order = CustomerOrderEntity.builder()
                .orderNumber(generateOrderNumber())
                .partnerAppId(partnerAppId)
                .userId(userId)
                .externalOrderId(req.externalOrderId())
                .shippingAddress(shipping)
                .billingAddress(billing)
                .status(OrderStatus.PENDING)
                .currency("USD")
                .notes(req.notes())
                .placedAt(Instant.now())
                .build();

        int subtotal = 0;
        for (var itemReq : req.items()) {
            ProductEntity product = productRepository.findById(itemReq.productId())
                    .orElseThrow(() -> new NotFoundException("Product not found: " + itemReq.productId()));
            ProductVariantEntity variant = itemReq.variantId() == null ? null :
                    variantRepository.findById(itemReq.variantId())
                            .orElseThrow(() -> new NotFoundException("Variant not found: " + itemReq.variantId()));

            BigDecimal unitPrice = variant != null && variant.getPrice() != null ? variant.getPrice() : product.getBasePrice();
            if (unitPrice == null) {
                throw new BusinessException("Product " + product.getSlug() + " has no price");
            }
            int unitCents = unitPrice.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValue();
            int lineTotal = unitCents * itemReq.quantity();

            order.getItems().add(OrderItemEntity.builder()
                    .order(order)
                    .product(product)
                    .variant(variant)
                    .titleSnapshot(product.getTitleZh())
                    .imageUrlSnapshot(product.getImages().isEmpty() ? null : product.getImages().get(0).getSourceUrl())
                    .skuSnapshot(variant != null ? variant.getSku() : null)
                    .unitPriceCents(unitCents)
                    .costCents(unitCents)
                    .quantity(itemReq.quantity())
                    .lineTotalCents(lineTotal)
                    .build());

            subtotal += lineTotal;
        }

        order.setSubtotalCents(subtotal);
        order.setShippingCents(0); // TODO: per-supplier shipping calc
        order.setTaxCents(0);
        order.setTotalCents(subtotal);

        orderRepository.save(order);
        return toView(order);
    }

    @Transactional(readOnly = true)
    public OrderView getOrder(UUID id) {
        return toView(orderRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Order not found: " + id)));
    }

    @Transactional(readOnly = true)
    public List<OrderView> listForPartner(UUID partnerAppId) {
        return orderRepository.findByPartnerAppId(partnerAppId).stream().map(this::toView).toList();
    }

    /* ============================================================
     *  Partner orders use-cases (moved out of PartnerOrderController)
     * ============================================================ */

    /** Creates an order for the partner resolved from the JWT and returns it as a DTO. */
    @Transactional
    public PartnerOrderDtoOut createOrderForPartner(Jwt jwt, CreateOrderRequest req) {
        return partnerOrderDtoMapper.toDtoOut(createOrder(resolvePartnerId(jwt), null, req));
    }

    /** Lists the orders of the partner resolved from the JWT as DTOs. */
    @Transactional(readOnly = true)
    public List<PartnerOrderDtoOut> listForPartner(Jwt jwt) {
        return partnerOrderDtoMapper.toDtoOutList(listForPartner(resolvePartnerId(jwt)));
    }

    /** Returns a single order as a partner DTO. */
    @Transactional(readOnly = true)
    public PartnerOrderDtoOut getPartnerOrder(UUID id) {
        return partnerOrderDtoMapper.toDtoOut(getOrder(id));
    }

    /**
     * Resolves the partner app id from the JWT subject.
     * For demo: derives a deterministic UUID from the subject. In production this
     * would look up the partner_app by the client_id claim.
     */
    private UUID resolvePartnerId(Jwt jwt) {
        String sub = jwt.getSubject();
        return UUID.nameUUIDFromBytes(("partner:" + sub).getBytes());
    }

    /* ============================================================
     *  Admin orders use-cases (moved out of AdminOrderController)
     * ============================================================ */

    /** Admin paginated listing with status + free-text search, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<AdminOrderRowDtoOut> listAdminOrders(String status, String q, int page, int size) {
        String needle = q == null ? "" : q.trim().toLowerCase();
        List<CustomerOrderEntity> all = orderRepository.findAll().stream()
                .filter(o -> status == null || status.isBlank() || o.getStatus().name().equalsIgnoreCase(status))
                .filter(o -> needle.isEmpty()
                        || (o.getOrderNumber() != null && o.getOrderNumber().toLowerCase().contains(needle))
                        || (o.getExternalOrderId() != null && o.getExternalOrderId().toLowerCase().contains(needle))
                        || (o.getShippingAddress() != null && o.getShippingAddress().getFullName() != null
                            && o.getShippingAddress().getFullName().toLowerCase().contains(needle)))
                .sorted((a, b) -> {
                    Instant ai = a.getPlacedAt() != null ? a.getPlacedAt() : a.getCreatedAt();
                    Instant bi = b.getPlacedAt() != null ? b.getPlacedAt() : b.getCreatedAt();
                    return bi.compareTo(ai);
                })
                .toList();
        int total = all.size();
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        List<AdminOrderRowDtoOut> items = all.subList(from, to).stream().map(this::toAdminRow).toList();
        var pageable = PageRequest.of(page, Math.max(1, size));
        return PageResponse.from(new PageImpl<>(items, pageable, total));
    }

    /** Admin order detail with line items and shipping address block. */
    @Transactional(readOnly = true)
    public AdminOrderDetailDtoOut getAdminOrderDetail(UUID id) {
        CustomerOrderEntity o = orderRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Order not found"));
        return toAdminDetail(o);
    }

    /** Move a payable order to FORWARDED and publish {@code order.forwarded}. */
    @Transactional
    public AdminOrderRowDtoOut forwardOrder(UUID id) {
        CustomerOrderEntity o = orderRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Order not found"));
        if (o.getStatus() == OrderStatus.PENDING || o.getStatus() == OrderStatus.AWAITING_PAYMENT
                || o.getStatus() == OrderStatus.PAID) {
            o.setStatus(OrderStatus.FORWARDED);
            o.setForwardedAt(Instant.now());
            orderRepository.save(o);
        }
        return publishAndRow(o, "order.forwarded");
    }

    /** Mark order SHIPPED and publish {@code order.shipped}. */
    @Transactional
    public AdminOrderRowDtoOut shipOrder(UUID id) {
        CustomerOrderEntity o = orderRepository.findById(id).orElseThrow();
        o.setStatus(OrderStatus.SHIPPED);
        o.setShippedAt(Instant.now());
        orderRepository.save(o);
        return publishAndRow(o, "order.shipped");
    }

    /** Mark order DELIVERED and publish {@code order.delivered}. */
    @Transactional
    public AdminOrderRowDtoOut deliverOrder(UUID id) {
        CustomerOrderEntity o = orderRepository.findById(id).orElseThrow();
        o.setStatus(OrderStatus.DELIVERED);
        o.setDeliveredAt(Instant.now());
        orderRepository.save(o);
        return publishAndRow(o, "order.delivered");
    }

    /** Mark order CANCELLED and publish {@code order.cancelled}. */
    @Transactional
    public AdminOrderRowDtoOut cancelOrder(UUID id) {
        CustomerOrderEntity o = orderRepository.findById(id).orElseThrow();
        o.setStatus(OrderStatus.CANCELLED);
        o.setCancelledAt(Instant.now());
        orderRepository.save(o);
        return publishAndRow(o, "order.cancelled");
    }

    /**
     * DROP-584: administrative refund of a PAID/FORWARDED/SHIPPED order. Marks the
     * order REFUNDED, credits the buyer wallet (idempotent by order id) and publishes
     * {@code order.refunded}. Does not refund at the external provider.
     */
    @Transactional
    public AdminOrderRowDtoOut refundOrder(UUID id) {
        CustomerOrderEntity o = orderRepository.findById(id).orElseThrow();
        if (o.getStatus() == OrderStatus.REFUNDED) {
            return toAdminRow(o); // idempotent
        }
        if (o.getStatus() == OrderStatus.CANCELLED) {
            throw new BusinessException("Cannot refund a cancelled order");
        }
        long amountCents = o.getTotalCents();
        if (o.getUserId() != null && amountCents > 0) {
            walletService.deposit(o.getUserId(), amountCents, o.getId(),
                    "refund-" + o.getId(), "Refund order " + o.getOrderNumber());
        }
        o.setStatus(OrderStatus.REFUNDED);
        orderRepository.save(o);
        return publishAndRow(o, "order.refunded");
    }

    /** Quick demo order creator — disabled outside dev/staging (DROP-119 / DROP-169). */
    @Transactional
    public OrderView createDemoOrder() {
        if (!demoOrdersEnabled) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "Demo order creation is disabled in this environment.");
        }
        ProductEntity p = productRepository.findAll().stream()
                .filter(x -> x.getStatus() != null && x.getStatus().name().equals("ACTIVE"))
                .filter(x -> x.getBasePrice() != null)
                .findFirst().orElseThrow(() -> new NotFoundException("No active product to seed demo order"));
        var addr = new AddressInput("Demo Customer", "+34000000", "demo@nx036.local",
                "C/ Demo 1", null, "Madrid", "M", "28001", "ES");
        var req = new CreateOrderRequest("DEMO-" + Instant.now().getEpochSecond(),
                addr, null, List.of(new OrderItemInput(p.getId(), null, 2)), "demo order from admin panel");
        return createOrder(null, null, req);
    }

    /** Demo order creator returning the transport DtoOut (used by the admin controller). */
    @Transactional
    public PartnerOrderDtoOut createDemoOrderDto() {
        return partnerOrderDtoMapper.toDtoOut(createDemoOrder());
    }

    /* ============ Me orders use-cases ============ */

    /** Authenticated user's own orders, newest first. */
    @Transactional(readOnly = true)
    public List<MeOrderRowDtoOut> listMyOrders(UUID userId) {
        return orderRepository.findAll().stream()
                .filter(o -> userId.equals(o.getUserId()))
                .sorted((a, b) -> {
                    Instant ai = a.getPlacedAt() != null ? a.getPlacedAt() : a.getCreatedAt();
                    Instant bi = b.getPlacedAt() != null ? b.getPlacedAt() : b.getCreatedAt();
                    return bi.compareTo(ai);
                })
                .map(adminOrderMapper::toMeRow).toList();
    }

    /**
     * Checkout the authenticated user's cart. Creates the order, optionally charges
     * the wallet (DROP-549: only when method is WALLET) and publishes the
     * {@code notifications.order.placed} outbox event in the same transaction.
     */
    @Transactional
    public MeOrderDetailDtoOut checkout(UUID userId, MeCheckoutDtoIn req, String idem) {
        AddressInput addr = req.getShippingAddressInline();
        if (req.getShippingAddressId() != null) {
            UserAddressEntity saved = userAddressRepository.findById(req.getShippingAddressId())
                    .orElseThrow(() -> new NotFoundException("Address not found"));
            if (!saved.getUser().getId().equals(userId)) throw new NotFoundException("Address not found");
            addr = new AddressInput(saved.getFullName(), saved.getPhone(), null,
                    saved.getLine1(), saved.getLine2(), saved.getCity(),
                    saved.getState(), saved.getPostalCode(), saved.getCountry());
        }
        if (addr == null) throw new BusinessException("Shipping address is required");

        List<OrderItemInput> items = req.getItems().stream()
                .map(i -> new OrderItemInput(i.getProductId(), i.getVariantId(), i.getQuantity())).toList();

        var orderReq = new CreateOrderRequest(
                "ME-" + Instant.now().getEpochSecond(), addr, null, items, req.getNotes());
        var view = createOrder(null, userId, orderReq);

        // DROP-549: only charge the wallet when the requested method is WALLET.
        // For CARD/PAYPAL/USDT the order stays PENDING and the client follows up
        // with /me/orders/{id}/payment-intent for the external flow.
        String method = req.getPaymentMethod() == null ? "WALLET" : req.getPaymentMethod().toUpperCase();
        CustomerOrderEntity o = orderRepository.findById(view.id()).orElseThrow();
        if ("WALLET".equals(method)) {
            long charge = (long) (view.total().doubleValue() * 100);
            String idemKey = idem != null ? idem : ("checkout-" + view.id());
            walletService.charge(userId, charge, view.id(), idemKey, "Order " + view.orderNumber());
            o.setStatus(OrderStatus.PAID);
        } else {
            o.setStatus(OrderStatus.PENDING);
        }
        orderRepository.save(o);

        // Plan 300k: publish to the notifications outbox in the same tx as the order
        // so we never end up with an "order without notification".
        userRepository.findById(userId).ifPresent(u -> notificationsPublisher.orderPlaced(
                userId, u.getEmail(), view.orderNumber(),
                view.total().toPlainString(), view.currency(), u.getLanguage()));

        return MeOrderDetailDtoOut.from(o);
    }

    /** Order detail for the authenticated user (ownership-checked). */
    @Transactional(readOnly = true)
    public MeOrderDetailDtoOut getMyOrderDetail(UUID userId, UUID id, String lang) {
        var o = orderRepository.findById(id).orElseThrow(() -> new NotFoundException("Order"));
        if (!userId.equals(o.getUserId())) throw new NotFoundException("Order");
        return MeOrderDetailDtoOut.from(o, lang);
    }

    /* ============ Admin row/detail mapping helpers ============ */

    private AdminOrderRowDtoOut publishAndRow(CustomerOrderEntity o, String eventType) {
        AdminOrderRowDtoOut row = toAdminRow(o);
        webhooks.publish(eventType, o.getId().toString(), toWebhookPayload(row));
        return row;
    }

    private AdminOrderRowDtoOut toAdminRow(CustomerOrderEntity o) {
        AdminOrderRowDtoOut.AdminOrderRowDtoOutBuilder b = adminOrderMapper.toRow(o).toBuilder();
        if (o.getUserId() != null) {
            userRepository.findById(o.getUserId()).ifPresent(u -> b.customerEmail(u.getEmail()));
            shopConnectionRepository.findByUser_IdOrderByCreatedAtDesc(o.getUserId()).stream().findFirst()
                    .ifPresent(sc -> b.shopName(sc.getShopHandle()).shopHandle(sc.getShopHandle()));
        }
        if (!o.getItems().isEmpty() && o.getItems().get(0).getProduct() != null
                && o.getItems().get(0).getProduct().getSupplier() != null) {
            b.supplierName(o.getItems().get(0).getProduct().getSupplier().getName());
        }
        return b.build();
    }

    private AdminOrderDetailDtoOut toAdminDetail(CustomerOrderEntity o) {
        AdminOrderRowDtoOut row = toAdminRow(o);
        AdminOrderAddressDtoOut addr = o.getShippingAddress() != null
                ? adminOrderMapper.toAddress(o.getShippingAddress()) : null;
        return AdminOrderDetailDtoOut.builder()
                .id(row.getId())
                .orderNumber(row.getOrderNumber())
                .status(row.getStatus())
                .partnerAppId(row.getPartnerAppId())
                .subtotalCents(row.getSubtotalCents())
                .shippingCents(row.getShippingCents())
                .totalCents(row.getTotalCents())
                .currency(row.getCurrency())
                .itemCount(row.getItemCount())
                .placedAt(row.getPlacedAt())
                .forwardedAt(row.getForwardedAt())
                .shippedAt(row.getShippedAt())
                .deliveredAt(row.getDeliveredAt())
                .cancelledAt(row.getCancelledAt())
                .customerEmail(row.getCustomerEmail())
                .shopName(row.getShopName())
                .shopHandle(row.getShopHandle())
                .supplierName(row.getSupplierName())
                .items(adminOrderMapper.toLines(o.getItems()))
                .shippingAddress(addr)
                .notes(o.getNotes())
                // Tracking is recorded asynchronously by the fulfilment service;
                // mirror what we already have on the order row.
                .trackingNumber(o.getExternalOrderId())
                .build();
    }

    /** Build the webhook envelope data payload, preserving the legacy row shape. */
    private Map<String, Object> toWebhookPayload(AdminOrderRowDtoOut row) {
        Map<String, Object> r = new HashMap<>();
        r.put("id", row.getId());
        r.put("orderNumber", row.getOrderNumber());
        r.put("status", row.getStatus());
        r.put("partnerAppId", row.getPartnerAppId());
        r.put("subtotalCents", row.getSubtotalCents());
        r.put("shippingCents", row.getShippingCents());
        r.put("totalCents", row.getTotalCents());
        r.put("currency", row.getCurrency());
        r.put("itemCount", row.getItemCount());
        r.put("placedAt", row.getPlacedAt());
        r.put("forwardedAt", row.getForwardedAt());
        r.put("shippedAt", row.getShippedAt());
        r.put("deliveredAt", row.getDeliveredAt());
        r.put("cancelledAt", row.getCancelledAt());
        if (row.getCustomerEmail() != null) r.put("customerEmail", row.getCustomerEmail());
        if (row.getShopName() != null) {
            r.put("shopName", row.getShopName());
            r.put("shopHandle", row.getShopHandle());
        }
        if (row.getSupplierName() != null) r.put("supplierName", row.getSupplierName());
        return r;
    }

    private AddressEntity toAddress(AddressInput in) {
        return AddressEntity.builder()
                .fullName(in.fullName())
                .phone(in.phone())
                .email(in.email())
                .line1(in.line1())
                .line2(in.line2())
                .city(in.city())
                .state(in.state())
                .postalCode(in.postalCode())
                .country(in.country())
                .build();
    }

    private OrderView toView(CustomerOrderEntity o) {
        List<OrderItemView> items = o.getItems().stream()
                .map(i -> new OrderItemView(
                        i.getProduct().getId(),
                        i.getVariant() != null ? i.getVariant().getId() : null,
                        i.getQuantity(),
                        BigDecimal.valueOf(i.getUnitPriceCents()).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(i.getLineTotalCents()).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)))
                .toList();
        return new OrderView(
                o.getId(),
                o.getOrderNumber(),
                o.getStatus().name(),
                BigDecimal.valueOf(o.getSubtotalCents()).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP),
                BigDecimal.valueOf(o.getShippingCents()).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP),
                BigDecimal.valueOf(o.getTaxCents()).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP),
                BigDecimal.valueOf(o.getTotalCents()).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP),
                o.getCurrency(),
                o.getPlacedAt(),
                o.getShippedAt(),
                items);
    }

    private String generateOrderNumber() {
        long ts = Instant.now().getEpochSecond();
        int rnd = RNG.nextInt(9000) + 1000;
        return "NX-" + ts + "-" + rnd;
    }
}
