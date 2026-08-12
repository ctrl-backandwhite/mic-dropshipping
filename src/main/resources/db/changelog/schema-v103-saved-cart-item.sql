--liquibase formatted sql

--changeset nexadrop:v103-001-saved-cart-item splitStatements:false
-- "Guardar para más tarde": líneas que el usuario aparta del carrito para comprarlas después. Ligado al
-- usuario (no al dispositivo), así aparece en cualquier navegador/app tras iniciar sesión. El carrito
-- activo sigue siendo local del navegador; solo esta lista se persiste en backend.
--
-- Se guarda un SNAPSHOT de la línea (title/imagen/precio/variante) además de las referencias, porque al
-- re-importar un producto sus variantes se recrean y el variant_id cambia: por eso variant_id NO lleva FK
-- (quedaría huérfano) y conservamos los datos para poder pintar la línea aunque la variante ya no exista.
CREATE TABLE IF NOT EXISTS saved_cart_item (
    id                  uuid          PRIMARY KEY,
    user_id             uuid          NOT NULL,
    product_id          uuid          NOT NULL,
    variant_id          uuid,
    sku                 varchar(120),
    slug                varchar(300)  NOT NULL,
    title               varchar(500)  NOT NULL,
    image_url           text,
    variant_label       varchar(300),
    unit_price_source   numeric(18,4) NOT NULL,
    source_currency     varchar(8)    NOT NULL,
    quantity            integer       NOT NULL DEFAULT 1,
    moq                 integer,
    unit_price_display  numeric(18,4),
    display_currency    varchar(8),
    display_symbol      varchar(8),
    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    created_by          varchar(120),
    updated_by          varchar(120),
    CONSTRAINT fk_saved_cart_item_user    FOREIGN KEY (user_id)    REFERENCES users(id)   ON DELETE CASCADE,
    CONSTRAINT fk_saved_cart_item_product FOREIGN KEY (product_id) REFERENCES product(id) ON DELETE CASCADE
);

-- Unicidad por (usuario, producto, variante). Como variant_id puede ser NULL (producto sin variante) y en
-- SQL NULL != NULL rompería la unicidad, se normaliza con COALESCE a un UUID centinela para el índice.
CREATE UNIQUE INDEX IF NOT EXISTS uq_saved_cart_item_user_product_variant
    ON saved_cart_item (user_id, product_id, COALESCE(variant_id, '00000000-0000-0000-0000-000000000000'::uuid));

CREATE INDEX IF NOT EXISTS idx_saved_cart_item_user ON saved_cart_item (user_id, created_at DESC);
