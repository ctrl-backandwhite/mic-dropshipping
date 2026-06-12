--liquibase formatted sql

--changeset nexadrop:v43-user-marketing-optout
-- Preferencia de email: los correos transaccionales (pedido, seguridad) siempre se envían;
-- el opt-out aplica a marketing/afiliados/newsletter.
ALTER TABLE users ADD COLUMN IF NOT EXISTS marketing_opt_out boolean NOT NULL DEFAULT false;

--changeset nexadrop:v43-newsletter-subscriber
CREATE TABLE IF NOT EXISTS newsletter_subscriber (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email       varchar(254) NOT NULL UNIQUE,
    user_id     uuid REFERENCES users(id) ON DELETE SET NULL,
    status      varchar(20) NOT NULL DEFAULT 'SUBSCRIBED',
    token       varchar(80) NOT NULL,
    source      varchar(40),
    created_at  timestamptz,
    updated_at  timestamptz,
    created_by  varchar(120),
    updated_by  varchar(120)
);
CREATE INDEX IF NOT EXISTS idx_newsletter_status ON newsletter_subscriber(status);

--changeset nexadrop:v43-newsletter-campaign
-- Histórico de newsletters enviadas (auditoría).
CREATE TABLE IF NOT EXISTS newsletter_campaign (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subject      varchar(300) NOT NULL,
    body_html    text NOT NULL,
    recipients   integer NOT NULL DEFAULT 0,
    status       varchar(20) NOT NULL DEFAULT 'SENT',
    created_at   timestamptz,
    updated_at   timestamptz,
    created_by   varchar(120),
    updated_by   varchar(120)
);
