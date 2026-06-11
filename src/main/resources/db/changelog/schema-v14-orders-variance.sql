--liquibase formatted sql
--changeset nexadrop:v14-orders-variance
-- DROP-407: aplicar variación de precio a órdenes demo que quedaron con $3.80 uniforme.
-- Multiplica totalCents por un factor pseudo-aleatorio derivado de uuid + ajusta qty x precio.

-- Solo afecta órdenes con order_number tipo NX-DEMO-* (las del seeder).
UPDATE customer_order
SET
    subtotal_cents = (subtotal_cents * (50 + (abs(hashtext(id::text)) % 250))) / 100,
    total_cents    = (total_cents    * (50 + (abs(hashtext(id::text)) % 250))) / 100
WHERE order_number LIKE 'NX-DEMO-%';

-- También arreglar items: aplica el mismo factor por order_id para mantener coherencia.
UPDATE order_item
SET
    unit_price_cents = (unit_price_cents * (50 + (abs(hashtext(order_id::text)) % 250))) / 100,
    line_total_cents = (line_total_cents * (50 + (abs(hashtext(order_id::text)) % 250))) / 100
WHERE order_id IN (SELECT id FROM customer_order WHERE order_number LIKE 'NX-DEMO-%');

-- Garantizar mínimo legible (1 USD)
UPDATE customer_order  SET total_cents = 100    WHERE total_cents < 100 AND order_number LIKE 'NX-DEMO-%';
UPDATE order_item      SET unit_price_cents = 100, line_total_cents = 100 * quantity
    WHERE unit_price_cents < 100 AND order_id IN (SELECT id FROM customer_order WHERE order_number LIKE 'NX-DEMO-%');
