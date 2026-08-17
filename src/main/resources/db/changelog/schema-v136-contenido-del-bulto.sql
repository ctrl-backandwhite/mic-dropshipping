--liquibase formatted sql

--changeset nexadrop:v136-001-order-shipment-item splitStatements:false
-- QUÉ LLEVA CADA BULTO. Un pedido que no cabe en una guía se reparte en varias, y ese reparto ya se
-- calcula al crear los envíos (ParcelSplitter sabe de qué línea del pedido viene cada unidad). Pero no
-- se guardaba en ninguna parte: el seguimiento enseñaba «Paquete 1/2» y «Paquete 2/2» sin decir qué iba
-- dentro de cada uno, así que quien recibía uno no sabía a qué le estaba siguiendo la pista.
--
-- La cantidad se guarda porque una misma línea puede partirse entre bultos: cinco unidades de una
-- chaqueta pueden ir tres en un paquete y dos en otro cuando el peso no cabe en una sola guía.
--
-- order_item_id NO lleva clave ajena a propósito, por el mismo motivo que en cart_item: las líneas se
-- borran con el pedido y aquí basta con quedar huérfano sin romper la inserción.
CREATE TABLE IF NOT EXISTS order_shipment_item (
    id            uuid    PRIMARY KEY,
    shipment_id   uuid    NOT NULL REFERENCES order_shipment(id) ON DELETE CASCADE,
    order_item_id uuid    NOT NULL,
    quantity      integer NOT NULL,
    CONSTRAINT order_shipment_item_qty_positiva CHECK (quantity > 0),
    CONSTRAINT order_shipment_item_unica UNIQUE (shipment_id, order_item_id)
);

-- El seguimiento pinta los bultos de un pedido y, de cada uno, su contenido: se consulta siempre por
-- bulto.
CREATE INDEX IF NOT EXISTS idx_order_shipment_item_shipment ON order_shipment_item (shipment_id);
