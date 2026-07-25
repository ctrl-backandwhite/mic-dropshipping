--liquibase formatted sql

--changeset nexadrop:v86-order-referral-discount
-- Descuento de referido (programa de afiliados) aplicado al COMPRADOR en el pedido. En céntimos USD,
-- igual que los demás importes del pedido. 0 = sin descuento. El descuento se calcula solo sobre el
-- subtotal de producto; el envío y el IVA se calculan sobre (subtotal − descuento), y total_cents ya
-- incluye la resta de este descuento. No aplica con el propio código del comprador (auto-referido).
ALTER TABLE customer_order ADD COLUMN IF NOT EXISTS discount_cents INTEGER NOT NULL DEFAULT 0;
