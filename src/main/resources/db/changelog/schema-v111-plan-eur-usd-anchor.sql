--liquibase formatted sql

--changeset nexa:v111-plan-eur-anchor-columns
-- Anclas de precio por divisa para los planes. La UE se factura en EUR y el resto del mundo en USD,
-- con importes "redondos" fijados a mano (no una conversión pura uno de otro). Las columnas base
-- (price_monthly_cents / price_yearly_cents) pasan a estar en USD (currency='USD'); estas dos guardan
-- el ancla en EUR para los países de la UE. El resto de divisas se derivan del ancla USD (conversión
-- del día + redondeo) en el backend.
ALTER TABLE subscription_plan ADD COLUMN IF NOT EXISTS price_monthly_eur_cents integer NOT NULL DEFAULT 0;
ALTER TABLE subscription_plan ADD COLUMN IF NOT EXISTS price_yearly_eur_cents integer NOT NULL DEFAULT 0;

--changeset nexa:v111-plan-anchor-values
-- Inicial (STARTER): 50 € / 60 $ al mes. Pro: 150 € / 160 $ al mes. Anual = 10 meses (2 gratis).
-- Base en USD; override EUR para la UE.
UPDATE subscription_plan SET currency='USD',
    price_monthly_cents=6000,  price_yearly_cents=60000,
    price_monthly_eur_cents=5000, price_yearly_eur_cents=50000
  WHERE code='STARTER';

UPDATE subscription_plan SET currency='USD',
    price_monthly_cents=16000, price_yearly_cents=160000,
    price_monthly_eur_cents=15000, price_yearly_eur_cents=150000
  WHERE code='PRO';
