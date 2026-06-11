--liquibase formatted sql
--changeset nexadrop:v15-wallet-holds
-- DROP-411 + DROP-438: poblar holdCents en algunas wallets demo para que la columna
-- "Retenido" en /admin/wallets muestre datos representativos en lugar de "—" siempre.

-- Cualquier wallet con balance > $50 que NO esté FROZEN: pone un hold pseudoaleatorio
-- (10..30% del balance) en órdenes "demo" en estado FORWARDED.
UPDATE wallet
SET hold_usd_cents = LEAST(
        balance_usd_cents,
        ((balance_usd_cents * (10 + (abs(hashtext(id::text)) % 21))) / 100)
    )
WHERE balance_usd_cents > 5000
  AND status = 'ACTIVE'
  AND (abs(hashtext(id::text)) % 3) = 0;   -- ~33% de las wallets ACTIVE

COMMENT ON COLUMN wallet.hold_usd_cents IS
  'Reservas pendientes (USD cents). En seed demo, algunas wallets tienen hold > 0 para reflejar órdenes en pipeline.';
