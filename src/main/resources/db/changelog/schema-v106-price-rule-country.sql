--liquibase formatted sql

--changeset nexadrop:v106-001-price-rule-country splitStatements:false
-- Margen por PAÍS: permite configurar un margen distinto según el país de destino del comprador (p. ej.
-- subir el margen en la UE para absorber la comisión de prepago de IVA). NULL = la regla aplica a cualquier
-- país (comportamiento actual); una regla con país concreto gana sobre la equivalente sin país cuando el
-- país efectivo del comprador coincide. Neutro hasta que se creen reglas por país.
ALTER TABLE price_rule ADD COLUMN IF NOT EXISTS country_code varchar(2);

CREATE INDEX IF NOT EXISTS idx_price_rule_country ON price_rule (country_code) WHERE country_code IS NOT NULL;
