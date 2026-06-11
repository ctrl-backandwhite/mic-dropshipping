--liquibase formatted sql
--changeset nexadrop:v22-order-totals-reconcile
-- DROP-536: el QA reporta órdenes con SUBTOTAL 4,13 € vs TOTAL 469,80 € sin
-- desglose. El subtotal de la orden ya no concuerda con la suma de sus items
-- (algunas migraciones previas tocaron uno u otro lado por separado).
-- Reconciliamos: subtotal_cents = SUM(items.line_total_cents) y
-- total_cents = subtotal + shipping + tax para que la cabecera coincida con el
-- desglose mostrado en /me/orders/{id}.

UPDATE customer_order o
SET subtotal_cents = COALESCE((
    SELECT SUM(i.line_total_cents)
    FROM order_item i WHERE i.order_id = o.id
), 0);

UPDATE customer_order
SET total_cents = subtotal_cents + COALESCE(shipping_cents, 0) + COALESCE(tax_cents, 0);
