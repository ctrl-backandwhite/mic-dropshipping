--liquibase formatted sql

--changeset nexadrop:v63-001 splitStatements:true endDelimiter:;
--comment: Coste en YUAN (CNY) por línea, congelado al crear la orden. Base para la comisión del operador (15% del CNY antes de margen), independiente de la tasa del día futura.
ALTER TABLE order_item ADD COLUMN IF NOT EXISTS cost_cny_cents BIGINT NOT NULL DEFAULT 0;

--changeset nexadrop:v63-002 splitStatements:true endDelimiter:;
--comment: Histórico de operaciones de los operadores (soporte). Cada vez que un OPERATOR entrega (DELIVERED) una orden se registra aquí, con la comisión del 15% del CNY. Fuente de verdad en Postgres (paginable por rango de fechas) + se indexa en OpenSearch.
CREATE TABLE IF NOT EXISTS operator_order_action (
    id                  UUID PRIMARY KEY,
    operator_subject    VARCHAR(80)  NOT NULL,
    operator_email      VARCHAR(160),
    operator_name       VARCHAR(160),
    order_id            UUID         NOT NULL,
    order_number        VARCHAR(40),
    action              VARCHAR(20)  NOT NULL DEFAULT 'DELIVERED',
    commission_cny_cents BIGINT      NOT NULL DEFAULT 0,
    item_count          INT          NOT NULL DEFAULT 0,
    processed_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          VARCHAR(255),
    updated_by          VARCHAR(255),
    CONSTRAINT uq_operator_action_order UNIQUE (order_id, action)
);

--changeset nexadrop:v63-003 splitStatements:true endDelimiter:;
--comment: Índices para consultar el histórico por operador y por rango de fechas (paginado).
CREATE INDEX IF NOT EXISTS idx_operator_action_subject_date ON operator_order_action (operator_subject, processed_at DESC);
CREATE INDEX IF NOT EXISTS idx_operator_action_date ON operator_order_action (processed_at DESC);
