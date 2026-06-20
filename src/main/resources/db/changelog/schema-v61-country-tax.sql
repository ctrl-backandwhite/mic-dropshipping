--liquibase formatted sql

--changeset nexadrop:v61-country-tax-rate
-- Impuesto (IVA/sales tax) configurable por país de envío. rate_bps = puntos básicos (2100 = 21%).
-- Se aplica sobre (subtotal + envío) al crear el pedido si el país tiene una tasa activa.
CREATE TABLE country_tax_rate (
    id            UUID PRIMARY KEY,
    country_code  VARCHAR(2)  NOT NULL,
    label         VARCHAR(80),
    rate_bps      INTEGER     NOT NULL DEFAULT 0,
    active        BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ,
    created_by    VARCHAR(64),
    updated_by    VARCHAR(64),
    CONSTRAINT uq_country_tax_country UNIQUE (country_code)
);
