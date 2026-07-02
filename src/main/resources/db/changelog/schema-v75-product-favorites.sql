--liquibase formatted sql

--changeset nexadrop:v75-001-product-favorites
-- Favoritos (wishlist) de productos por usuario. Un usuario no puede tener el mismo producto dos veces
-- (UNIQUE). Se borra en cascada si se elimina el usuario o el producto.
CREATE TABLE IF NOT EXISTS product_favorite (
    id          uuid        PRIMARY KEY,
    user_id     uuid        NOT NULL,
    product_id  uuid        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    created_by  varchar(120),
    updated_by  varchar(120),
    CONSTRAINT uq_product_favorite_user_product UNIQUE (user_id, product_id),
    CONSTRAINT fk_product_favorite_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_product_favorite_product FOREIGN KEY (product_id) REFERENCES product(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_product_favorite_user ON product_favorite(user_id);
CREATE INDEX IF NOT EXISTS idx_product_favorite_product ON product_favorite(product_id);
