--liquibase formatted sql

--changeset nexadrop:v5-webhooks-001 splitStatements:true endDelimiter:;
--comment: Outbound webhook subscriptions + delivery audit (HMAC-SHA256, retries, idempotency).

CREATE TABLE IF NOT EXISTS webhook_subscription (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid REFERENCES users(id) ON DELETE CASCADE,
    name         varchar(120) NOT NULL,
    target_url   varchar(800) NOT NULL,
    secret       varchar(120) NOT NULL,
    events       jsonb NOT NULL DEFAULT '[]'::jsonb,
    active       boolean NOT NULL DEFAULT true,
    description  varchar(400),
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_webhook_sub_user   ON webhook_subscription(user_id);
CREATE INDEX IF NOT EXISTS idx_webhook_sub_active ON webhook_subscription(active);

CREATE TABLE IF NOT EXISTS webhook_delivery (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id uuid NOT NULL REFERENCES webhook_subscription(id) ON DELETE CASCADE,
    event_type      varchar(80)  NOT NULL,
    event_id        varchar(120) NOT NULL,
    payload         jsonb        NOT NULL,
    signature       varchar(200) NOT NULL,
    target_url      varchar(800) NOT NULL,
    status          varchar(20)  NOT NULL,   -- PENDING | SUCCESS | FAILED | RETRY
    attempt         int          NOT NULL DEFAULT 0,
    response_status int,
    response_body   text,
    next_retry_at   timestamptz,
    last_attempt_at timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_webhook_delivery_sub      ON webhook_delivery(subscription_id);
CREATE INDEX IF NOT EXISTS idx_webhook_delivery_status   ON webhook_delivery(status, next_retry_at);
CREATE INDEX IF NOT EXISTS idx_webhook_delivery_event_id ON webhook_delivery(event_id);
