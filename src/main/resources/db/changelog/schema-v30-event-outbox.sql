--liquibase formatted sql
--changeset nexadrop:v30-event-outbox
-- Plan 300k req/min — Fase 3: outbox pattern para garantizar entrega
-- transaccional de eventos a Kafka. Antes, kafkaTemplate.send dentro de
-- una transacción podía fallar SIN rollback de la transacción de BD —
-- generando órdenes "pagadas" sin email de confirmación, etc. Ahora cada
-- evento se inserta en event_outbox dentro de la MISMA transacción que
-- el cambio de dominio; un worker lee la tabla y publica a Kafka con
-- garantía at-least-once.

CREATE TABLE IF NOT EXISTS event_outbox (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(80)  NOT NULL,    -- 'Order', 'Wallet', 'Product'…
    aggregate_id    VARCHAR(120) NOT NULL,    -- ID del agregado afectado
    topic           VARCHAR(120) NOT NULL,
    partition_key   VARCHAR(200),             -- usado como key de Kafka (sticky)
    payload         JSONB        NOT NULL,
    headers         JSONB,                    -- headers extra (tracing, etc.)
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',  -- PENDING | SENT | FAILED
    attempts        INT          NOT NULL DEFAULT 0,
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- El worker pollea por status=PENDING ordenado por next_attempt_at.
CREATE INDEX IF NOT EXISTS idx_event_outbox_pending
  ON event_outbox(status, next_attempt_at)
  WHERE status = 'PENDING';

-- Para depuración / auditoría: índice por agregado.
CREATE INDEX IF NOT EXISTS idx_event_outbox_aggregate
  ON event_outbox(aggregate_type, aggregate_id, created_at DESC);
