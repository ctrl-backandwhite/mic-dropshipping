--liquibase formatted sql

--changeset nexadrop:v50-category-attribute-schema
-- DROP-670: esquema de atributos esperado por categoría. Define qué atributos (clave + etiqueta) se
-- esperan en los productos de una categoría, para sugerirlos en el editor y validar en la importación.
CREATE TABLE IF NOT EXISTS category_attribute_schema (
    id uuid PRIMARY KEY,
    category_id uuid NOT NULL REFERENCES category(id) ON DELETE CASCADE,
    attr_key varchar(60) NOT NULL,
    label varchar(120),
    required boolean NOT NULL DEFAULT false,
    position int NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz,
    created_by varchar(120),
    updated_by varchar(120),
    CONSTRAINT uq_cat_attr_schema UNIQUE (category_id, attr_key)
);

CREATE INDEX IF NOT EXISTS idx_cat_attr_schema_cat ON category_attribute_schema (category_id);
