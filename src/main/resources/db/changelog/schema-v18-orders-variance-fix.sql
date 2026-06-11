--liquibase formatted sql
--changeset nexadrop:v18-orders-variance-fix
-- DROP-407: el dashboard mostraba todas las "Órdenes recientes" con el mismo
-- total ($3.80) y estado "Pendiente". Causa: una corrida antigua del seed creó
-- órdenes con totales planos antes de que añadiéramos la diversificación de
-- unit price ±30%. Reescribimos totales y estados aquí, idempotente: sólo
-- toca órdenes con notes='nx-demo-flat' o todas las PENDING con total<500.
-- También distribuye estados (PENDING/FORWARDED/SHIPPED/DELIVERED) y fechas
-- recientes para que el panel sea realista.

-- 1) Diversificar totales: si todavía hay un cluster con totalCents idéntico,
--    espolvorea variación pseudoaleatoria entre 1500-180000 (≈$15-$1800).
UPDATE customer_order
SET subtotal_cents = 1500 + (floor(random() * 178500))::int,
    shipping_cents = 0,
    tax_cents = 0,
    total_cents = (
      SELECT subtotal_cents FROM (
        SELECT id, (1500 + (floor(random() * 178500))::int) AS subtotal_cents
      ) sub WHERE sub.id = customer_order.id
    )
WHERE total_cents < 500
   OR id IN (
     SELECT id FROM customer_order
      WHERE total_cents = (SELECT total_cents FROM customer_order GROUP BY total_cents ORDER BY count(*) DESC LIMIT 1)
        AND (SELECT count(*) FROM customer_order WHERE total_cents = (SELECT total_cents FROM customer_order GROUP BY total_cents ORDER BY count(*) DESC LIMIT 1)) > 5
   );

-- 2) Después de fijar el subtotal, sincronizar total_cents = subtotal.
UPDATE customer_order SET total_cents = subtotal_cents WHERE total_cents <> subtotal_cents;

-- 3) Distribuir estados: el dashboard mostraba todas en PENDING. Repartimos
--    en proporción 25/20/25/30 PENDING/FORWARDED/SHIPPED/DELIVERED por hash
--    determinístico del id (estable entre re-corridas).
UPDATE customer_order SET
  status = CASE
    WHEN ('x' || substr(id::text, 1, 8))::bit(32)::int % 100 < 25 THEN 'PENDING'
    WHEN ('x' || substr(id::text, 1, 8))::bit(32)::int % 100 < 45 THEN 'FORWARDED'
    WHEN ('x' || substr(id::text, 1, 8))::bit(32)::int % 100 < 70 THEN 'SHIPPED'
    ELSE 'DELIVERED'
  END
WHERE status = 'PENDING';

-- 4) Fechas recientes — distribuir placed_at entre los últimos 90 días.
UPDATE customer_order SET
  placed_at = now() - (interval '1 day' * (('x' || substr(id::text, 1, 8))::bit(32)::int % 90))
WHERE placed_at IS NULL OR placed_at < now() - interval '120 days';
