package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.dto.PartnerDtos.AddressInput;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.CreateOrderRequest;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.OrderItemInput;
import com.nexaplatform.dropshipping.api.dto.in.MeCheckoutDtoIn;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.notifications.NotificationsPublisher;
import com.nexaplatform.dropshipping.application.service.WebhookDispatcherService;
import com.nexaplatform.dropshipping.application.usecase.OrderUseCase;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserAddressEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ShopConnectionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserAddressRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Customer-order use case. Operates on the {@link Order} domain model and delegates
 * persistence to the domain port. Cross-aggregate read enrichment (customer email,
 * shop name/handle, supplier name) is filled here and carried on the model, mirroring
 * {@code Category.productCount}. Logic moved verbatim out of the legacy
 * {@code OrderService}, preserving the checkout / lifecycle / notification semantics.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderUseCaseImpl implements OrderUseCase {

    private static final SecureRandom RNG = new SecureRandom();

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final UserRepository userRepository;
    private final ShopConnectionRepository shopConnectionRepository;
    private final UserAddressRepository userAddressRepository;
    private final WebhookDispatcherService webhooks;
    private final WalletUseCase walletUseCase;
    private final NotificationsPublisher notificationsPublisher;

    @Value("${nexadrop.demo.orders-enabled:false}")
    private boolean demoOrdersEnabled;

    @Override
    @Transactional
    public Order createOrder(UUID partnerAppId, UUID userId, CreateOrderRequest req) {
        if (req.items() == null || req.items().isEmpty()) {
            throw new BusinessException("Order must have at least one item");
        }

        Order order = Order.builder().orderNumber(generateOrderNumber()).partnerAppId(partnerAppId).userId(userId)
                .externalOrderId(req.externalOrderId()).status(OrderStatus.PENDING).currency("USD").notes(req.notes())
                .placedAt(Instant.now()).items(new ArrayList<>()).build();

        applyAddress(order, req.shippingAddress(), false);
        if (req.billingAddress() != null) {
            applyAddress(order, req.billingAddress(), true);
        }

        int subtotal = 0;
        for (var itemReq : req.items()) {
            ProductEntity product = productRepository.findById(itemReq.productId())
                    .orElseThrow(() -> new NotFoundException("Product not found: " + itemReq.productId()));
            ProductVariantEntity variant = itemReq.variantId() == null
                    ? null
                    : variantRepository.findById(itemReq.variantId())
                            .orElseThrow(() -> new NotFoundException("Variant not found: " + itemReq.variantId()));

            BigDecimal unitPrice = variant != null && variant.getPrice() != null
                    ? variant.getPrice()
                    : product.getBasePrice();
            if (unitPrice == null) {
                throw new BusinessException("Product " + product.getSlug() + " has no price");
            }
            int unitCents = unitPrice.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValue();
            int lineTotal = unitCents * itemReq.quantity();

            order.getItems().add(OrderItem.builder().productId(product.getId())
                    .variantId(variant != null ? variant.getId() : null).titleSnapshot(product.getTitleZh())
                    .imageUrlSnapshot(product.getImages().isEmpty() ? null : product.getImages().get(0).getSourceUrl())
                    .skuSnapshot(variant != null ? variant.getSku() : null).unitPriceCents(unitCents)
                    .costCents(unitCents).quantity(itemReq.quantity()).lineTotalCents(lineTotal).build());

            subtotal += lineTotal;
        }

        order.setSubtotalCents(subtotal);
        order.setShippingCents(0); // TODO: per-supplier shipping calc
        order.setTaxCents(0);
        order.setTotalCents(subtotal);

        return orderRepository.save(order);
    }

    @Override
    @Transactional(readOnly = true)
    public Order getOrder(UUID id) {
        return orderRepository.findById(id).orElseThrow(() -> new NotFoundException("Order not found: " + id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Order> listForPartner(UUID partnerAppId) {
        return orderRepository.findByPartnerAppId(partnerAppId);
    }

    /* ============ Partner (JWT) ============ */

    @Override
    @Transactional
    public Order createOrderForPartner(Jwt jwt, CreateOrderRequest req) {
        return createOrder(resolvePartnerId(jwt), null, req);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Order> listForPartner(Jwt jwt) {
        return listForPartner(resolvePartnerId(jwt));
    }

    @Override
    @Transactional(readOnly = true)
    public Order getPartnerOrder(UUID id) {
        return getOrder(id);
    }

    private UUID resolvePartnerId(Jwt jwt) {
        String sub = jwt.getSubject();
        return UUID.nameUUIDFromBytes(("partner:" + sub).getBytes());
    }

    /* ============ Admin ============ */

    @Override
    @Transactional(readOnly = true)
    public List<Order> listAdminOrders(String status, String q) {
        String needle = q == null ? "" : q.trim().toLowerCase();
        return orderRepository.findAll().stream()
                .filter(o -> status == null || status.isBlank() || o.getStatus().name().equalsIgnoreCase(status))
                .filter(o -> needle.isEmpty()
                        || (o.getOrderNumber() != null && o.getOrderNumber().toLowerCase().contains(needle))
                        || (o.getExternalOrderId() != null && o.getExternalOrderId().toLowerCase().contains(needle))
                        || (o.getShippingFullName() != null && o.getShippingFullName().toLowerCase().contains(needle)))
                .sorted((a, b) -> {
                    Instant ai = a.getPlacedAt() != null ? a.getPlacedAt() : a.getCreatedAt();
                    Instant bi = b.getPlacedAt() != null ? b.getPlacedAt() : b.getCreatedAt();
                    return bi.compareTo(ai);
                }).map(this::enrich).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Order getAdminOrderDetail(UUID id) {
        Order o = orderRepository.findById(id).orElseThrow(() -> new NotFoundException("Order not found"));
        return enrich(o);
    }

    @Override
    @Transactional
    public Order forwardOrder(UUID id) {
        Order o = orderRepository.findById(id).orElseThrow(() -> new NotFoundException("Order not found"));
        if (o.getStatus() == OrderStatus.PENDING || o.getStatus() == OrderStatus.AWAITING_PAYMENT
                || o.getStatus() == OrderStatus.PAID) {
            o.setStatus(OrderStatus.FORWARDED);
            o.setForwardedAt(Instant.now());
            o = orderRepository.save(o);
        }
        return publishAndEnrich(o, "order.forwarded");
    }

    @Override
    @Transactional
    public Order shipOrder(UUID id) {
        Order o = orderRepository.findById(id).orElseThrow();
        o.setStatus(OrderStatus.SHIPPED);
        o.setShippedAt(Instant.now());
        o = orderRepository.save(o);
        return publishAndEnrich(o, "order.shipped");
    }

    @Override
    @Transactional
    public Order deliverOrder(UUID id) {
        Order o = orderRepository.findById(id).orElseThrow();
        o.setStatus(OrderStatus.DELIVERED);
        o.setDeliveredAt(Instant.now());
        o = orderRepository.save(o);
        return publishAndEnrich(o, "order.delivered");
    }

    @Override
    @Transactional
    public Order cancelOrder(UUID id) {
        Order o = orderRepository.findById(id).orElseThrow();
        o.setStatus(OrderStatus.CANCELLED);
        o.setCancelledAt(Instant.now());
        o = orderRepository.save(o);
        return publishAndEnrich(o, "order.cancelled");
    }

    @Override
    @Transactional
    public Order refundOrder(UUID id) {
        Order o = orderRepository.findById(id).orElseThrow();
        if (o.getStatus() == OrderStatus.REFUNDED) {
            return enrich(o); // idempotent
        }
        if (o.getStatus() == OrderStatus.CANCELLED) {
            throw new BusinessException("Cannot refund a cancelled order");
        }
        long amountCents = o.getTotalCents();
        if (o.getUserId() != null && amountCents > 0) {
            walletUseCase.deposit(o.getUserId(), amountCents, o.getId(), "refund-" + o.getId(),
                    "Refund order " + o.getOrderNumber());
        }
        o.setStatus(OrderStatus.REFUNDED);
        o = orderRepository.save(o);
        return publishAndEnrich(o, "order.refunded");
    }

    @Override
    @Transactional
    public Order createDemoOrder() {
        if (!demoOrdersEnabled) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "Demo order creation is disabled in this environment.");
        }
        ProductEntity p = productRepository.findAll().stream()
                .filter(x -> x.getStatus() != null && x.getStatus().name().equals("ACTIVE"))
                .filter(x -> x.getBasePrice() != null).findFirst()
                .orElseThrow(() -> new NotFoundException("No active product to seed demo order"));
        var addr = new AddressInput("Demo Customer", "+34000000", "demo@nx036.local", "C/ Demo 1", null, "Madrid", "M",
                "28001", "ES");
        var req = new CreateOrderRequest("DEMO-" + Instant.now().getEpochSecond(), addr, null,
                List.of(new OrderItemInput(p.getId(), null, 2)), "demo order from admin panel");
        return createOrder(null, null, req);
    }

    /* ============ Me (B2C) ============ */

    @Override
    @Transactional(readOnly = true)
    public List<Order> listMyOrders(UUID userId) {
        return orderRepository.findAll().stream().filter(o -> userId.equals(o.getUserId())).sorted((a, b) -> {
            Instant ai = a.getPlacedAt() != null ? a.getPlacedAt() : a.getCreatedAt();
            Instant bi = b.getPlacedAt() != null ? b.getPlacedAt() : b.getCreatedAt();
            return bi.compareTo(ai);
        }).toList();
    }

    @Override
    @Transactional
    public Order checkout(UUID userId, MeCheckoutDtoIn req, String idem) {
        AddressInput addr = req.getShippingAddressInline();
        if (req.getShippingAddressId() != null) {
            UserAddressEntity saved = userAddressRepository.findById(req.getShippingAddressId())
                    .orElseThrow(() -> new NotFoundException("Address not found"));
            if (!saved.getUser().getId().equals(userId))
                throw new NotFoundException("Address not found");
            addr = new AddressInput(saved.getFullName(), saved.getPhone(), null, saved.getLine1(), saved.getLine2(),
                    saved.getCity(), saved.getState(), saved.getPostalCode(), saved.getCountry());
        }
        if (addr == null)
            throw new BusinessException("Shipping address is required");

        List<OrderItemInput> items = req.getItems().stream()
                .map(i -> new OrderItemInput(i.getProductId(), i.getVariantId(), i.getQuantity())).toList();

        var orderReq = new CreateOrderRequest("ME-" + Instant.now().getEpochSecond(), addr, null, items,
                req.getNotes());
        Order created = createOrder(null, userId, orderReq);

        // DROP-549: only charge the wallet when the requested method is WALLET.
        // For CARD/PAYPAL/USDT the order stays PENDING and the client follows up
        // with /me/orders/{id}/payment-intent for the external flow.
        String method = req.getPaymentMethod() == null ? "WALLET" : req.getPaymentMethod().toUpperCase();
        Order o = orderRepository.findById(created.getId()).orElseThrow();
        if ("WALLET".equals(method)) {
            long charge = (long) created.getTotalCents();
            String idemKey = idem != null ? idem : ("checkout-" + created.getId());
            walletUseCase.charge(userId, charge, created.getId(), idemKey, "Order " + created.getOrderNumber());
            o.setStatus(OrderStatus.PAID);
        } else {
            o.setStatus(OrderStatus.PENDING);
        }
        o = orderRepository.save(o);

        // Plan 300k: publish to the notifications outbox in the same tx as the order
        // so we never end up with an "order without notification".
        String totalPlain = BigDecimal.valueOf(created.getTotalCents())
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP).toPlainString();
        final String orderNumber = created.getOrderNumber();
        final String currency = created.getCurrency();
        userRepository.findById(userId).ifPresent(u -> notificationsPublisher.orderPlaced(userId, u.getEmail(),
                orderNumber, totalPlain, currency, u.getLanguage()));

        return o;
    }

    @Override
    @Transactional(readOnly = true)
    public Order getMyOrderDetail(UUID userId, UUID id, String lang) {
        Order o = orderRepository.findById(id).orElseThrow(() -> new NotFoundException("Order"));
        if (!userId.equals(o.getUserId()))
            throw new NotFoundException("Order");
        resolveItemTitles(o, lang);
        return o;
    }

    /* ============ Helpers ============ */

    /** Enriches the order, publishes the lifecycle webhook with the legacy payload shape. */
    private Order publishAndEnrich(Order o, String eventType) {
        Order enriched = enrich(o);
        webhooks.publish(eventType, o.getId().toString(), toWebhookPayload(enriched));
        return enriched;
    }

    /** Fills the cross-aggregate read fields (customerEmail/shopName/shopHandle/supplierName). */
    private Order enrich(Order o) {
        if (o.getUserId() != null) {
            userRepository.findById(o.getUserId()).ifPresent(u -> o.setCustomerEmail(u.getEmail()));
            shopConnectionRepository.findByUser_IdOrderByCreatedAtDesc(o.getUserId()).stream().findFirst()
                    .ifPresent(sc -> {
                        o.setShopName(sc.getShopHandle());
                        o.setShopHandle(sc.getShopHandle());
                    });
        }
        if (o.getItems() != null && !o.getItems().isEmpty() && o.getItems().get(0).getSupplierName() != null) {
            o.setSupplierName(o.getItems().get(0).getSupplierName());
        }
        return o;
    }

    /**
     * DROP-537: resolves each line's display title for the request language using the
     * legacy fallback chain product_translation[lang] -> [en] -> snapshot -> titleZh and
     * stores it back into {@code titleSnapshot} so the api mapper stays free of logic.
     */
    private void resolveItemTitles(Order o, String lang) {
        if (o.getItems() == null)
            return;
        for (OrderItem i : o.getItems()) {
            Map<String, String> titles = i.getProductTitles();
            String title = null;
            if (titles != null) {
                title = titles.get(lang == null ? null : lang.toLowerCase());
                if (title == null)
                    title = titles.get("en");
            }
            if (title == null) {
                title = i.getTitleSnapshot() != null ? i.getTitleSnapshot() : i.getProductTitleZh();
            }
            i.setTitleSnapshot(title);
        }
    }

    /** Build the webhook envelope data payload, preserving the legacy row shape. */
    private Map<String, Object> toWebhookPayload(Order o) {
        Map<String, Object> r = new HashMap<>();
        r.put("id", o.getId());
        r.put("orderNumber", o.getOrderNumber());
        r.put("status", o.getStatus().name());
        r.put("partnerAppId", o.getPartnerAppId());
        r.put("subtotalCents", o.getSubtotalCents());
        r.put("shippingCents", o.getShippingCents());
        r.put("totalCents", o.getTotalCents());
        r.put("currency", o.getCurrency());
        r.put("itemCount", o.getItems() != null ? o.getItems().size() : 0);
        r.put("placedAt", o.getPlacedAt());
        r.put("forwardedAt", o.getForwardedAt());
        r.put("shippedAt", o.getShippedAt());
        r.put("deliveredAt", o.getDeliveredAt());
        r.put("cancelledAt", o.getCancelledAt());
        if (o.getCustomerEmail() != null)
            r.put("customerEmail", o.getCustomerEmail());
        if (o.getShopName() != null) {
            r.put("shopName", o.getShopName());
            r.put("shopHandle", o.getShopHandle());
        }
        if (o.getSupplierName() != null)
            r.put("supplierName", o.getSupplierName());
        return r;
    }

    /** Applies an inline address onto the order's flat shipping/billing snapshot fields. */
    private void applyAddress(Order order, AddressInput in, boolean billing) {
        if (billing) {
            order.setBillingFullName(in.fullName());
            order.setBillingPhone(in.phone());
            order.setBillingEmail(in.email());
            order.setBillingLine1(in.line1());
            order.setBillingLine2(in.line2());
            order.setBillingCity(in.city());
            order.setBillingState(in.state());
            order.setBillingPostalCode(in.postalCode());
            order.setBillingCountry(in.country());
        } else {
            order.setShippingFullName(in.fullName());
            order.setShippingPhone(in.phone());
            order.setShippingEmail(in.email());
            order.setShippingLine1(in.line1());
            order.setShippingLine2(in.line2());
            order.setShippingCity(in.city());
            order.setShippingState(in.state());
            order.setShippingPostalCode(in.postalCode());
            order.setShippingCountry(in.country());
        }
    }

    private String generateOrderNumber() {
        long ts = Instant.now().getEpochSecond();
        int rnd = RNG.nextInt(9000) + 1000;
        return "NX-" + ts + "-" + rnd;
    }
}
