--liquibase formatted sql
--changeset nexadrop:v27-shop-listings-backfill
-- DROP-548: backfilleamos shop_product_listing para cada producto que YA tiene
-- órdenes asociadas a alguna tienda. Como customer_order no guarda FK directa
-- al shop, usamos `external_order_id` (campo donde el webhook deja el id de
-- pedido externo) para localizar la tienda más probable: nos quedamos con la
-- primera conexión activa del mismo usuario que el orden — eso cubre el caso
-- del QA (tienda admin con orden NX-...).

-- Usamos una CTE para deduplicar (conn_id, product_id) antes del INSERT,
-- evitando duplicados generados por múltiples órdenes que comparten la misma
-- conexión + producto.
INSERT INTO shop_product_listing (id, user_shop_connection_id, product_id, remote_product_id, status, created_at, updated_at)
SELECT gen_random_uuid(), pairs.conn_id, pairs.product_id, NULL, 'PUBLISHED', now(), now()
FROM (
    SELECT DISTINCT conn.id AS conn_id, oi.product_id
    FROM order_item oi
    JOIN customer_order o ON o.id = oi.order_id
    JOIN LATERAL (
        SELECT u.id FROM user_shop_connection u
        WHERE u.user_id = o.user_id
        ORDER BY u.created_at ASC
        LIMIT 1
    ) conn ON true
    WHERE o.user_id IS NOT NULL
      AND o.external_order_id IS NOT NULL
) pairs
WHERE NOT EXISTS (
    SELECT 1 FROM shop_product_listing l
    WHERE l.user_shop_connection_id = pairs.conn_id
      AND l.product_id = pairs.product_id
);
