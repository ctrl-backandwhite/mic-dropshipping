--liquibase formatted sql

--changeset nexadrop:v49-category-1688-mapping
-- DROP-677: mapeo de categorías de 1688 (id/nombre de origen) a la categoría interna, para resolver
-- automáticamente la categoría del producto al importar sin que el operador indique el slug.
CREATE TABLE IF NOT EXISTS category_1688_mapping (
    id uuid PRIMARY KEY,
    external_1688_id varchar(120) NOT NULL,
    external_1688_name varchar(300),
    category_id uuid NOT NULL REFERENCES category(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz,
    created_by varchar(120),
    updated_by varchar(120),
    CONSTRAINT uq_category_1688_external UNIQUE (external_1688_id)
);

CREATE INDEX IF NOT EXISTS idx_category_1688_name ON category_1688_mapping (lower(external_1688_name));
