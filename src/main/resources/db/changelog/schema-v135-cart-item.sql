--liquibase formatted sql

--changeset nexadrop:v135-001-cart-item splitStatements:false
-- CARRITO SINCRONIZADO. Hasta ahora el carrito activo vivía en el navegador (localStorage), así que era
-- del DISPOSITIVO y no de la persona: lo añadido en la web no aparecía en la app y al revés. Esta tabla
-- lo sube al servidor ligado al usuario, de forma que la cesta le siga entre navegador, móvil y app.
--
-- Es hermana de saved_cart_item (v103) y se guarda igual, con un SNAPSHOT de la línea
-- (título/imagen/variante/precio) además de las referencias, por dos motivos:
--   1) al re-importar un producto sus variantes se RECREAN y el variant_id cambia: por eso variant_id NO
--      lleva FK (quedaría huérfano) y conservamos lo necesario para poder pintar la línea igualmente;
--   2) el importe que se COBRA no sale de aquí — lo recalcula /api/catalog/cart-quote con el precio y la
--      tasa del día. Este precio es solo "de pintado": congelarlo aquí no vincula a la tienda.
CREATE TABLE IF NOT EXISTS cart_item (
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
    CONSTRAINT fk_cart_item_user    FOREIGN KEY (user_id)    REFERENCES users(id)   ON DELETE CASCADE,
    CONSTRAINT fk_cart_item_product FOREIGN KEY (product_id) REFERENCES product(id) ON DELETE CASCADE
);

-- La IDENTIDAD de una línea es (usuario, producto, variante): dos variantes del mismo producto son dos
-- líneas distintas y comprables por separado. Como variant_id puede ser NULL (producto sin variantes) y en
-- SQL NULL != NULL rompería la unicidad —se colarían filas duplicadas del mismo producto base—, se
-- normaliza con COALESCE a un UUID centinela dentro del índice, igual que en saved_cart_item.
CREATE UNIQUE INDEX IF NOT EXISTS uq_cart_item_user_product_variant
    ON cart_item (user_id, product_id, COALESCE(variant_id, '00000000-0000-0000-0000-000000000000'::uuid));

-- Toda lectura del carrito es "las líneas de ESTE usuario en su orden de llegada": el índice cubre el
-- filtro y el orden de una sola pasada.
CREATE INDEX IF NOT EXISTS idx_cart_item_user ON cart_item (user_id, created_at ASC);
