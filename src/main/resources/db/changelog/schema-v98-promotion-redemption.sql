--liquibase formatted sql

--changeset nexadrop:v98-promotion-redemption splitStatements:false
-- A quién sirve un cupón y cuántas veces.
--
-- v97 dejó el tope GLOBAL de usos (`max_uses`) y la vigencia por fechas, pero faltaban dos cosas que
-- se piden siempre:
--   - un cupón para UNA cuenta concreta (el detalle a un cliente enfadado, el código de un influencer)
--   - un tope POR PERSONA: sin él, un cupón de 100 usos se lo lleva entero quien lo publique en un foro
ALTER TABLE promotion
    -- Nulo = cualquiera puede canjearlo. Con valor = solo esa cuenta.
    ADD COLUMN IF NOT EXISTS user_id uuid REFERENCES users (id) ON DELETE CASCADE,
    -- Nulo = sin tope por persona (solo cuenta el global).
    ADD COLUMN IF NOT EXISTS max_uses_per_user integer;

CREATE INDEX IF NOT EXISTS idx_promotion_user ON promotion (user_id) WHERE user_id IS NOT NULL;

-- Quién canjeó qué. Hace falta para el tope por persona —que no se puede deducir de un contador
-- global— y para poder responder «¿este cliente ya usó el cupón?» sin rastrear pedidos a mano.
CREATE TABLE IF NOT EXISTS promotion_redemption (
    id           uuid PRIMARY KEY,
    promotion_id uuid        NOT NULL REFERENCES promotion (id) ON DELETE CASCADE,
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    order_id     uuid REFERENCES customer_order (id) ON DELETE SET NULL,
    amount_cents integer     NOT NULL DEFAULT 0,
    redeemed_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_promotion_redemption_lookup
    ON promotion_redemption (promotion_id, user_id);
-- Un pedido no puede canjear dos veces el mismo cupón: un reintento de pago duplicaría el descuento.
CREATE UNIQUE INDEX IF NOT EXISTS uq_promotion_redemption_order
    ON promotion_redemption (promotion_id, order_id) WHERE order_id IS NOT NULL;

COMMENT ON COLUMN promotion.user_id IS
    'Cuenta a la que se restringe el cupón. Nulo = disponible para cualquiera.';
COMMENT ON COLUMN promotion.max_uses_per_user IS
    'Tope de canjes por persona. Nulo = solo aplica el tope global de max_uses.';
COMMENT ON TABLE promotion_redemption IS
    'Canjes de cupones: sostiene el tope por persona y deja el rastro de quién usó qué.';
