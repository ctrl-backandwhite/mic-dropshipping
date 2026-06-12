--liquibase formatted sql

--changeset nexadrop:v40-affiliate-terms
-- DROP-650: alta explícita al programa con aceptación de términos registrada.
ALTER TABLE affiliate ADD COLUMN IF NOT EXISTS accepted_terms_at timestamptz;

--changeset nexadrop:v40-affiliate-config-fraud
-- DROP-652: límites configurables anti-fraude.
ALTER TABLE affiliate_program_config ADD COLUMN IF NOT EXISTS max_commission_period_cents bigint NOT NULL DEFAULT 0;
ALTER TABLE affiliate_program_config ADD COLUMN IF NOT EXISTS max_period_days integer NOT NULL DEFAULT 30;
ALTER TABLE affiliate_program_config ADD COLUMN IF NOT EXISTS click_dedup_minutes integer NOT NULL DEFAULT 30;

--changeset nexadrop:v40-affiliate-payout
-- DROP-651: liquidación de comisiones (payouts) con aprobación del operador e histórico auditable.
CREATE TABLE IF NOT EXISTS affiliate_payout (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    affiliate_id  uuid NOT NULL REFERENCES affiliate(id) ON DELETE CASCADE,
    amount_cents  bigint NOT NULL,
    currency      varchar(8) NOT NULL,
    status        varchar(20) NOT NULL DEFAULT 'REQUESTED',
    method        varchar(20) NOT NULL DEFAULT 'WALLET',
    wallet_tx_id  uuid,
    commission_count integer NOT NULL DEFAULT 0,
    note          varchar(300),
    requested_at  timestamptz,
    processed_at  timestamptz,
    created_at    timestamptz,
    updated_at    timestamptz,
    created_by    varchar(120),
    updated_by    varchar(120)
);
CREATE INDEX IF NOT EXISTS idx_affiliate_payout_affiliate ON affiliate_payout(affiliate_id);
CREATE INDEX IF NOT EXISTS idx_affiliate_payout_status ON affiliate_payout(status);

--changeset nexadrop:v40-affiliate-commission-payout-link
-- Vincula cada comisión al payout que la liquidó (trazabilidad/conciliación).
ALTER TABLE affiliate_commission ADD COLUMN IF NOT EXISTS payout_id uuid;
CREATE INDEX IF NOT EXISTS idx_affiliate_commission_payout ON affiliate_commission(payout_id);
