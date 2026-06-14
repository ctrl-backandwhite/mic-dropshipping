--liquibase formatted sql

--changeset nexadrop:v51-variant-value-translation
-- Traducción por idioma de cada valor de variación (color/talla/estampado). value_zh es el origen
-- canónico; aquí se guarda la etiqueta visible por idioma (es/en/pt/zh/…). La columna value de
-- variant_value se mantiene como override neutral (compatibilidad).
CREATE TABLE IF NOT EXISTS variant_value_translation (
    id uuid PRIMARY KEY,
    variant_value_id uuid NOT NULL REFERENCES variant_value(id) ON DELETE CASCADE,
    language varchar(8) NOT NULL,
    value varchar(200) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz,
    created_by varchar(120),
    updated_by varchar(120),
    CONSTRAINT uq_variant_value_lang UNIQUE (variant_value_id, language)
);
