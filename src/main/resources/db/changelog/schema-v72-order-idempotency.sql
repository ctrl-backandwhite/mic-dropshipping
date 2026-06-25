--liquibase formatted sql

--changeset nexadrop:v72-order-idempotency
-- Idempotencia a nivel de orden: el checkout creaba una orden NUEVA en cada llamada
-- (el idem previo solo protegía el cargo del wallet, no la creación de la orden), así
-- que un intento abandonado dejaba una orden PENDING y el reintento creaba otra. Se
-- añade la clave de idempotencia (hash del carrito) para reutilizar la orden aún sin
-- pagar. NO es única: tras pagar y volver a comprar el mismo carrito, la nueva orden
-- puede repetir el hash (el reuso solo aplica a órdenes no pagadas).
ALTER TABLE customer_order ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(80);

CREATE INDEX IF NOT EXISTS idx_customer_order_user_idem
    ON customer_order (user_id, idempotency_key);
