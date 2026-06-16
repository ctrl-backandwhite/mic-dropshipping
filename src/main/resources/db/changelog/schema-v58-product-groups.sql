--liquibase formatted sql

--changeset nexadrop:v58-product-groups
-- Grupos de productos: colecciones arbitrarias de productos para aplicarles una regla de margen común
-- (nuevo scope PRODUCT_GROUP en price_rule). Un producto puede pertenecer a varios grupos.
CREATE TABLE IF NOT EXISTS product_group (
    id          uuid PRIMARY KEY,
    name        varchar(150) NOT NULL,
    description varchar(400),
    active      boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz,
    created_by  varchar(120),
    updated_by  varchar(120)
);

CREATE TABLE IF NOT EXISTS product_group_member (
    group_id   uuid NOT NULL REFERENCES product_group(id) ON DELETE CASCADE,
    product_id uuid NOT NULL REFERENCES product(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (group_id, product_id)
);

-- Resolver rápido los grupos de un producto (camino de pricing) y los miembros de un grupo (admin).
CREATE INDEX IF NOT EXISTS idx_pgm_product ON product_group_member (product_id);
CREATE INDEX IF NOT EXISTS idx_pgm_group ON product_group_member (group_id);
