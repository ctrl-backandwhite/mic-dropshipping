--liquibase formatted sql
--changeset nexadrop:v12-payment-order-link
-- Add order_id + purpose to payment so partner can pay a specific dropship order
-- via CARD/PAYPAL/USDT without going through wallet recharge.

ALTER TABLE payment
    ADD COLUMN IF NOT EXISTS order_id UUID,
    ADD COLUMN IF NOT EXISTS purpose  VARCHAR(20) NOT NULL DEFAULT 'WALLET_RECHARGE';

-- Allowed values: WALLET_RECHARGE (existing flow), ORDER_PAYMENT (new partner flow)
COMMENT ON COLUMN payment.purpose IS 'WALLET_RECHARGE | ORDER_PAYMENT';

-- Add a soft foreign key by index — partner orders live in customer_order.
CREATE INDEX IF NOT EXISTS idx_payment_order_id ON payment(order_id) WHERE order_id IS NOT NULL;
