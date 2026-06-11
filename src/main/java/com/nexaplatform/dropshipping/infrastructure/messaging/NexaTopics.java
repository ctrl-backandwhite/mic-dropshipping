package com.nexaplatform.dropshipping.infrastructure.messaging;

public final class NexaTopics {
    public static final String CRAWL_REQUESTS = "crawl.requests";
    public static final String PRODUCT_RAW = "product.raw";
    public static final String PRODUCT_INGESTED = "product.ingested";
    public static final String PRODUCT_NORMALIZED = "product.normalized";
    public static final String PRODUCT_TRANSLATED = "product.translated";
    public static final String IMAGE_FETCH = "image.fetch";
    public static final String IMAGE_MIRRORED = "image.mirrored";
    public static final String ORDER_EVENTS = "order.events";
    public static final String SUBSCRIPTION_EVENTS = "subscription.events";

    /* ============================================================
     * Plan 300k — desacople hacia mic-notificationservice (proyecto
     * ecommerce). El backend dropshipping PUBLICA eventos a estos
     * topics; el microservicio de notificaciones es responsable de
     * decidir canal (email/push/SMS), plantilla y delivery.
     * ============================================================ */

    /** Notificaciones transaccionales — email/push genéricos. */
    public static final String NOTIFICATIONS_DISPATCH = "notifications.dispatch";

    /** Confirmaciones de pedido (consume mic-notificationservice). */
    public static final String NOTIFICATIONS_ORDER_PLACED   = "notifications.order.placed";
    public static final String NOTIFICATIONS_ORDER_SHIPPED  = "notifications.order.shipped";
    public static final String NOTIFICATIONS_ORDER_DELIVERED = "notifications.order.delivered";

    /** Recargas de wallet / cargos (consume mic-notificationservice). */
    public static final String NOTIFICATIONS_WALLET_RECHARGED = "notifications.wallet.recharged";
    public static final String NOTIFICATIONS_WALLET_CHARGED   = "notifications.wallet.charged";

    /** Auth: reset password, activación, 2FA, etc. */
    public static final String NOTIFICATIONS_AUTH = "notifications.auth";

    /** Webhooks salientes pendientes de entrega — consumido por dispatcher dedicado. */
    public static final String WEBHOOK_DISPATCH = "webhook.dispatch";

    /** Audit log — desacoplado del request síncrono. */
    public static final String AUDIT_LOG = "audit.log";

    /** Cache invalidation — purga CDN/Redis tras cambios de catálogo. */
    public static final String CACHE_INVALIDATION = "cache.invalidation";

    private NexaTopics() {}
}
