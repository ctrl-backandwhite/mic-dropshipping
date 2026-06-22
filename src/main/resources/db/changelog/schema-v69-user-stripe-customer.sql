--liquibase formatted sql

--changeset nexadrop:v69-user-stripe-customer
-- Identificador del Customer de Stripe asociado al usuario. Se crea/persiste de forma perezosa la
-- primera vez que el usuario guarda una tarjeta o contrata un plan; reutilizado para sus suscripciones.
ALTER TABLE users ADD COLUMN IF NOT EXISTS stripe_customer_id VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_users_stripe_customer ON users (stripe_customer_id);
