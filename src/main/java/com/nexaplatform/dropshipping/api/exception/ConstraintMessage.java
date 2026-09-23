package com.nexaplatform.dropshipping.api.exception;

/**
 * Traduce las restricciones de base de datos (unique/check) a mensajes claros y accionables para el
 * usuario, de modo que un fallo nunca exponga SQL crudo y la persona sepa exactamente qué corregir.
 * Mantener sincronizado con las constraints reales del esquema (Liquibase / {@code pg_constraint}).
 */
public enum ConstraintMessage {

    // --- Cuentas de usuario ---
    UK_USERS_PHONE("uk_users_phone",
            "Ese número de teléfono ya está registrado en otra cuenta. Usa un número distinto."),

    // --- Catálogo: productos y traducciones ---
    UX_PRODTR_PROD_LANG("ux_prodtr_prod_lang",
            "Ese producto ya existe en el catálogo (mismo origen + externalId) y ya tiene sus traducciones. "
                    + "No se puede importar dos veces el mismo producto: cambia el externalId o edita/elimina el existente."), UX_PRODUCT_SOURCE_EXTERNAL(
                            "ux_product_source_external",
                            "Ya existe un producto con ese externalId para el mismo origen (p. ej. 1688). Usa un externalId distinto."), PRODUCT_SLUG_KEY(
                                    "product_slug_key",
                                    "Ya existe un producto con ese título/slug. Cambia ligeramente el título para que sea único."), PRODUCT_TAG_SLUG_KEY(
                                            "product_tag_slug_key",
                                            "Ya existe una etiqueta con ese slug."), PRODUCT_HISTORY_KEY(
                                                    "product_history_product_id_snapshot_date_key",
                                                    "Ya hay un histórico de ese producto para esa fecha."), PRODUCT_WAREHOUSE_STOCK(
                                                            "product_warehouse_stock_product_id_warehouse_id_key",
                                                            "Ese producto ya tiene stock registrado en ese almacén. Edita el stock existente en lugar de crear otro."), PRODUCT_REVIEW_RATING_CHECK(
                                                                    "product_review_rating_check",
                                                                    "La valoración de la reseña debe estar entre 1 y 5."), UQ_VARIANT_VALUE_LANG(
                                                                            "uq_variant_value_lang",
                                                                            "Ese valor de variante ya tiene una traducción para ese idioma."),

    // --- Catálogo: categorías ---
    CATEGORY_SLUG_KEY("category_slug_key",
            "Ya existe una categoría con ese slug. Usa un slug distinto."), UX_CATTR_CAT_LANG("ux_cattr_cat_lang",
                    "La categoría ya tiene una traducción para ese idioma."), UQ_CATEGORY_1688_EXTERNAL(
                            "uq_category_1688_external",
                            "Ese id de categoría de 1688 ya está mapeado a una categoría interna."), UQ_CAT_ATTR_SCHEMA(
                                    "uq_cat_attr_schema", "Ese atributo ya está definido para la categoría."),

    // --- Proveedores / almacenes / envío ---
    UX_SUPPLIER_SOURCE_EXTERNAL("ux_supplier_source_external",
            "Ya existe un proveedor con ese externalId para el mismo origen."), WAREHOUSE_CODE_KEY("warehouse_code_key",
                    "Ya existe un almacén con ese código."), SHIPPING_ZONE("shipping_zone_supplier_id_country_code_key",
                            "Ya existe una zona de envío para ese proveedor y país."), CAINIAO_ZONE(
                                    "cainiao_shipping_zone_country_code_key",
                                    "Ya existe una zona de envío Cainiao para ese país."),

    // --- Usuarios / pedidos / pagos / moneda ---
    USERS_EMAIL_KEY("users_email_key", "Ya existe un usuario con ese email."), NEWSLETTER_EMAIL_KEY(
            "newsletter_subscriber_email_key",
            "Ese email ya está suscrito al boletín."), CUSTOMER_ORDER_NUMBER_KEY("customer_order_order_number_key",
                    "Ya existe un pedido con ese número."), PAYMENT_IDEMPOTENCY("payment_idempotency_key_key",
                            "Ese pago ya se procesó (clave de idempotencia repetida)."), CURRENCY_RATE_CODE_KEY(
                                    "currency_rate_code_key",
                                    "Ya existe una tasa de cambio para esa moneda."), WALLET_USER("wallet_user_id_key",
                                            "Ese usuario ya tiene un wallet."),

    // --- Planes / suscripciones / tienda ---
    SUBSCRIPTION_PLAN_CODE_KEY("subscription_plan_code_key", "Ya existe un plan con ese código."), UX_PLANFEAT_PLAN_KEY(
            "ux_planfeat_plan_key",
            "Esa característica ya está definida para el plan."), UX_SHOP_PLATFORM_HANDLE("ux_shop_platform_handle",
                    "Ya existe una tienda conectada con esa plataforma y handle."), STORE_LANGUAGE_CODE(
                            "uq_store_language_code", "Ese idioma de tienda ya está dado de alta."),

    // --- Carrito sincronizado ---
    // Solo salta en una carrera: dos dispositivos añadiendo la MISMA línea a la vez, cuando ninguno de
    // los dos la ve todavía guardada. La cesta no se pierde; basta con repetir la última acción.
    UQ_CART_ITEM_USER_PRODUCT_VARIANT("uq_cart_item_user_product_variant",
            "Esa línea se estaba actualizando desde otro dispositivo. Vuelve a intentarlo: tu carrito no se ha perdido."),

    // --- Afiliados ---
    AFFILIATE_CODE_KEY("affiliate_code_key", "Ese código de afiliado ya está en uso."), AFFILIATE_USER_KEY(
            "affiliate_user_id_key", "Ese usuario ya está dado de alta como afiliado.");

    private final String constraint;
    private final String message;

    ConstraintMessage(String constraint, String message) {
        this.constraint = constraint;
        this.message = message;
    }

    public String constraint() {
        return constraint;
    }

    public String message() {
        return message;
    }

    /** Mensaje claro para el nombre de constraint dado, o {@code null} si no está mapeada. */
    public static String forConstraint(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String n = name.trim().toLowerCase();
        for (ConstraintMessage c : values()) {
            if (c.constraint.equals(n)) {
                return c.message;
            }
        }
        return null;
    }
}
