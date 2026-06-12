--liquibase formatted sql

--changeset nexadrop:v39-affiliate-status
-- DROP-643: programa de afiliados. La tabla 'affiliate' era un stub (code + earnings).
-- Añadimos el ciclo de estado (pending/active/suspended) y un override de comisión por afiliado.
ALTER TABLE affiliate ADD COLUMN IF NOT EXISTS status varchar(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE affiliate ADD COLUMN IF NOT EXISTS commission_percent_override numeric(6,3);
-- los afiliados existentes activos quedan ACTIVE; los inactivos, SUSPENDED
UPDATE affiliate SET status = CASE WHEN active THEN 'ACTIVE' ELSE 'SUSPENDED' END;

--changeset nexadrop:v39-affiliate-referral-code
-- Un afiliado puede tener uno o varios códigos/enlaces de referido (DROP-644).
CREATE TABLE IF NOT EXISTS affiliate_referral_code (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    affiliate_id uuid NOT NULL REFERENCES affiliate(id) ON DELETE CASCADE,
    code         varchar(40) NOT NULL UNIQUE,
    label        varchar(120),
    active       boolean NOT NULL DEFAULT true,
    clicks       integer NOT NULL DEFAULT 0,
    created_at   timestamptz,
    updated_at   timestamptz,
    created_by   varchar(120),
    updated_by   varchar(120)
);
CREATE INDEX IF NOT EXISTS idx_affiliate_referral_code_code ON affiliate_referral_code(code);
CREATE INDEX IF NOT EXISTS idx_affiliate_referral_code_affiliate ON affiliate_referral_code(affiliate_id);
-- migrar el código existente de cada afiliado como su código primario
INSERT INTO affiliate_referral_code (id, affiliate_id, code, label, active, created_at, updated_at)
SELECT gen_random_uuid(), a.id, a.code, 'Primary', a.active, now(), now()
FROM affiliate a
WHERE a.code IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM affiliate_referral_code c WHERE c.code = a.code);

--changeset nexadrop:v39-affiliate-attribution
-- Captura de clic + cookie (last-click) durante la ventana de atribución (DROP-645).
CREATE TABLE IF NOT EXISTS affiliate_attribution (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    referral_code_id uuid NOT NULL REFERENCES affiliate_referral_code(id) ON DELETE CASCADE,
    affiliate_id     uuid NOT NULL REFERENCES affiliate(id) ON DELETE CASCADE,
    visitor_token    varchar(80),
    referred_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    clicked_at       timestamptz NOT NULL,
    expires_at       timestamptz NOT NULL,
    created_at       timestamptz,
    updated_at       timestamptz
);
CREATE INDEX IF NOT EXISTS idx_affiliate_attr_visitor ON affiliate_attribution(visitor_token);
CREATE INDEX IF NOT EXISTS idx_affiliate_attr_user ON affiliate_attribution(referred_user_id);

--changeset nexadrop:v39-affiliate-attribution-audit
-- AuditableEntity exige created_by/updated_by también en attribution.
ALTER TABLE affiliate_attribution ADD COLUMN IF NOT EXISTS created_by varchar(120);
ALTER TABLE affiliate_attribution ADD COLUMN IF NOT EXISTS updated_by varchar(120);

--changeset nexadrop:v39-affiliate-conversion
-- Conversión: una venta atribuida a un afiliado (DROP-645/646). Idempotente por pedido.
CREATE TABLE IF NOT EXISTS affiliate_conversion (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    affiliate_id     uuid NOT NULL REFERENCES affiliate(id) ON DELETE CASCADE,
    referral_code_id uuid REFERENCES affiliate_referral_code(id) ON DELETE SET NULL,
    referred_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    order_id         uuid NOT NULL REFERENCES customer_order(id) ON DELETE CASCADE,
    base_amount_cents bigint NOT NULL,
    currency         varchar(8) NOT NULL,
    status           varchar(20) NOT NULL DEFAULT 'PENDING',
    created_at       timestamptz,
    updated_at       timestamptz,
    created_by       varchar(120),
    updated_by       varchar(120),
    CONSTRAINT uq_affiliate_conversion_order UNIQUE (order_id)
);
CREATE INDEX IF NOT EXISTS idx_affiliate_conv_affiliate ON affiliate_conversion(affiliate_id);
CREATE INDEX IF NOT EXISTS idx_affiliate_conv_user ON affiliate_conversion(referred_user_id);

--changeset nexadrop:v39-affiliate-commission
-- Comisión calculada por el motor (DROP-646). Estados pending/approved/paid/rejected.
CREATE TABLE IF NOT EXISTS affiliate_commission (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    affiliate_id  uuid NOT NULL REFERENCES affiliate(id) ON DELETE CASCADE,
    conversion_id uuid NOT NULL REFERENCES affiliate_conversion(id) ON DELETE CASCADE,
    amount_cents  bigint NOT NULL,
    currency      varchar(8) NOT NULL,
    percentage    numeric(6,3) NOT NULL,
    status        varchar(20) NOT NULL DEFAULT 'PENDING',
    approved_at   timestamptz,
    paid_at       timestamptz,
    wallet_tx_id  uuid,
    note          varchar(300),
    created_at    timestamptz,
    updated_at    timestamptz,
    created_by    varchar(120),
    updated_by    varchar(120),
    CONSTRAINT uq_affiliate_commission_conversion UNIQUE (conversion_id)
);
CREATE INDEX IF NOT EXISTS idx_affiliate_comm_affiliate ON affiliate_commission(affiliate_id);
CREATE INDEX IF NOT EXISTS idx_affiliate_comm_status ON affiliate_commission(status);

--changeset nexadrop:v39-affiliate-program-config
-- Configuración global del programa (DROP-643). Una sola fila.
CREATE TABLE IF NOT EXISTS affiliate_program_config (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    default_percent         numeric(6,3) NOT NULL,
    attribution_window_days integer NOT NULL,
    return_period_days      integer NOT NULL,
    min_payout_cents        bigint NOT NULL,
    currency                varchar(8) NOT NULL,
    attribution_model       varchar(20) NOT NULL DEFAULT 'LAST_CLICK',
    created_at              timestamptz,
    updated_at              timestamptz,
    created_by              varchar(120),
    updated_by              varchar(120)
);
-- seed de configuración por defecto: 10%, ventana 30 días, devolución 14 días, pago mínimo 50 EUR
INSERT INTO affiliate_program_config
    (id, default_percent, attribution_window_days, return_period_days, min_payout_cents, currency, attribution_model, created_at, updated_at)
SELECT gen_random_uuid(), 10.000, 30, 14, 5000, 'EUR', 'LAST_CLICK', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM affiliate_program_config);
