--liquibase formatted sql

--changeset nexa:v118-moq-margin-setting
-- Ajuste de margen para productos con pedido mínimo (MOQ > 1): reducen el margen que les corresponda a un
-- factor (por defecto 50%). Es un MODIFICADOR global (no una fila de price_rule, porque no es un margen
-- propio sino "la mitad del que aplique"), así que se guarda en su propia tabla de una sola fila (id = 1).
CREATE TABLE IF NOT EXISTS moq_margin_setting (
    id             smallint     PRIMARY KEY DEFAULT 1,
    enabled        boolean      NOT NULL DEFAULT true,
    factor_percent numeric(6,2) NOT NULL DEFAULT 50,
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT moq_margin_setting_singleton CHECK (id = 1)
);

INSERT INTO moq_margin_setting (id, enabled, factor_percent)
VALUES (1, true, 50)
ON CONFLICT (id) DO NOTHING;
