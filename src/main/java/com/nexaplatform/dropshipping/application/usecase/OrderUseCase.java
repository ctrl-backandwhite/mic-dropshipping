package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.api.dto.PartnerDtos.CreateOrderRequest;
import com.nexaplatform.dropshipping.api.dto.in.MeCheckoutDtoIn;
import com.nexaplatform.dropshipping.application.BaseUseCase;
import com.nexaplatform.dropshipping.domain.model.Order;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.UUID;

/**
 * Use-case port for customer orders; operates on the {@link Order} domain model.
 * Covers partner (B2B), admin and authenticated-customer (B2C) flows. Logic moved
 * out of the legacy {@code OrderService}; admin/me read rows are enriched in the
 * use case (customerEmail/shopName/supplierName) and carried on the model.
 */
public interface OrderUseCase extends BaseUseCase<Order, Order, UUID> {

    /* ============ Core ============ */

    /** Creates an order, computing totals and snapshotting line items. */
    Order createOrder(UUID partnerAppId, UUID userId, CreateOrderRequest req);

    /** Single order by id (throws when missing). */
    Order getOrder(UUID id);

    /** Orders placed through the given partner application. */
    List<Order> listForPartner(UUID partnerAppId);

    /* ============ Partner (JWT) ============ */

    /** Creates an order for the partner resolved from the JWT. */
    Order createOrderForPartner(Jwt jwt, CreateOrderRequest req);

    /** Lists the orders of the partner resolved from the JWT. */
    List<Order> listForPartner(Jwt jwt);

    /** Single order for a partner. */
    Order getPartnerOrder(UUID id);

    /* ============ Admin ============ */

    /** Admin filtered listing (status + free-text), newest first, enriched. */
    List<Order> listAdminOrders(String status, String q);

    /** Admin order detail (enriched, with items + shipping address), localised to {@code lang}. */
    Order getAdminOrderDetail(UUID id, String lang);

    /** Move a payable order to FORWARDED and publish {@code order.forwarded}. */
    Order forwardOrder(UUID id);

    /** Mark order SHIPPED and publish {@code order.shipped}. */
    Order shipOrder(UUID id);

    /** Mark order DELIVERED and publish {@code order.delivered}. */
    Order deliverOrder(UUID id);

    /** Mark order CANCELLED and publish {@code order.cancelled}. */
    Order cancelOrder(UUID id);

    /** Administrative refund: mark REFUNDED, credit the buyer wallet and publish {@code order.refunded}. */
    Order refundOrder(UUID id);

    /** Quick demo order creator — disabled outside dev/staging. */
    Order createDemoOrder();

    /* ============ Me (B2C) ============ */

    /** Authenticated user's own orders, newest first. */
    List<Order> listMyOrders(UUID userId);

    /** Checkout the authenticated user's cart, optionally charging the wallet, and publish the outbox event. */
    Order checkout(UUID userId, MeCheckoutDtoIn req, String idem);

    /** Order detail for the authenticated user (ownership-checked); resolves the request language. */
    Order getMyOrderDetail(UUID userId, UUID id, String lang);
}
