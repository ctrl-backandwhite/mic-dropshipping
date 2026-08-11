--liquibase formatted sql

--changeset nexadrop:v96-supplier-purchase splitStatements:false
-- La compra al proveedor: el tramo que hasta ahora no existía en ningún sitio.
--
-- El pedido del cliente se marcaba FORWARDED a mano y eso disparaba la creación de la guía
-- internacional. Pero entre "el cliente ha pagado" y "hay un bulto que enviar" faltan tres cosas que
-- ocurren en el mundo real y que nadie registraba: comprar el producto en 1688, que el proveedor lo
-- mande al almacén de Dongguan, y que el almacén lo reciba. Sin esos hitos se podía —y se puede hoy—
-- generar y pagar una guía internacional para mercancía que todavía no se ha comprado.
--
-- La unidad es (pedido, proveedor), no la línea de pedido: el proveedor manda UN paquete con todo lo
-- que se le compre para ese pedido, y el número de seguimiento nacional es de ese paquete. Dos líneas
-- del mismo proveedor son una sola compra; dos proveedores en el mismo pedido son dos compras que
-- luego se consolidan bajo un único número YT.
CREATE TABLE IF NOT EXISTS supplier_purchase (
    id                uuid PRIMARY KEY,
    order_id          uuid        NOT NULL REFERENCES customer_order (id) ON DELETE CASCADE,
    supplier_id       uuid        NOT NULL REFERENCES supplier (id),
    status            varchar(32) NOT NULL DEFAULT 'PENDING',

    -- Compra en 1688: se paga a mano con Alipay, así que la referencia y el coste los teclea el admin.
    -- cost_cny_cents es el precio REALMENTE pagado, no el del catálogo: es la única forma de saber el
    -- margen de verdad, porque el proveedor cambia precios y el envío nacional varía.
    purchase_ref      varchar(120),
    cost_cny_cents    bigint,
    shipping_cny_cents bigint,
    purchased_at      timestamptz,

    -- Envío del proveedor al almacén chino. El número nacional es obligatorio para dar de alta la orden
    -- de re-empaquetado: Yunfulfillment empareja por él el bulto físico con las instrucciones.
    domestic_tracking varchar(64),
    domestic_carrier  varchar(64),
    shipped_at        timestamptz,

    -- Recepción y re-empaquetado en el almacén.
    warehouse_code    varchar(32) NOT NULL DEFAULT 'CNCHASHAN',
    received_at       timestamptz,
    pack_order_no     varchar(64),
    pack_service_type varchar(40),
    pack_submitted_at timestamptz,

    notes             text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz,
    CONSTRAINT uq_supplier_purchase UNIQUE (order_id, supplier_id)
);

-- La cola de trabajo del admin: "qué tengo que comprar hoy". Se consulta por estado constantemente, y
-- ordenada por antigüedad para que lo más viejo se compre antes.
CREATE INDEX IF NOT EXISTS idx_supplier_purchase_status
    ON supplier_purchase (status, created_at);
CREATE INDEX IF NOT EXISTS idx_supplier_purchase_order
    ON supplier_purchase (order_id);

-- El seguimiento nacional identifica el bulto en el almacén: dos compras no pueden compartirlo o el
-- almacén no sabría a cuál aplicar las instrucciones. Admite nulos mientras el proveedor no ha enviado.
CREATE UNIQUE INDEX IF NOT EXISTS uq_supplier_purchase_domestic
    ON supplier_purchase (domestic_tracking) WHERE domestic_tracking IS NOT NULL;

-- Qué líneas del pedido cubre cada compra. Una línea pertenece a UNA compra —la de su proveedor—, de
-- ahí el índice único: si una línea apareciera en dos compras se pagaría dos veces.
CREATE TABLE IF NOT EXISTS supplier_purchase_item (
    id            uuid PRIMARY KEY,
    purchase_id   uuid    NOT NULL REFERENCES supplier_purchase (id) ON DELETE CASCADE,
    order_item_id uuid    NOT NULL REFERENCES order_item (id) ON DELETE CASCADE,
    quantity      integer NOT NULL,
    CONSTRAINT uq_supplier_purchase_item UNIQUE (order_item_id)
);

CREATE INDEX IF NOT EXISTS idx_supplier_purchase_item_purchase
    ON supplier_purchase_item (purchase_id);

COMMENT ON TABLE supplier_purchase IS
    'Compra al proveedor de 1688 para un pedido. Una fila por (pedido, proveedor), que es un bulto.';
COMMENT ON COLUMN supplier_purchase.cost_cny_cents IS
    'Coste REAL pagado en 1688, en céntimos de CNY. No es el precio de catálogo.';
COMMENT ON COLUMN supplier_purchase.domestic_tracking IS
    'Seguimiento nacional chino. Yunfulfillment empareja por él el bulto con la orden de re-empaquetado.';
COMMENT ON COLUMN supplier_purchase.pack_order_no IS
    'Número de la orden de re-empaquetado en el OMS de Yunfulfillment, una vez dada de alta.';
