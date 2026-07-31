--liquibase formatted sql

--changeset nexadrop:v92-order-shipments splitStatements:false
-- Un pedido puede viajar en VARIAS guías.
--
-- Hasta ahora el número de seguimiento vivía en una columna del pedido, lo que da por hecho que todo
-- cabe en un bulto. No siempre: cada producto logístico impone un peso y un valor máximos (el canal de
-- pruebas BPA rechaza más de 2 kg o más de $24 declarados), así que un carrito grande hay que repartirlo
-- en varios envíos y cada uno tiene su guía, su etiqueta y su propia trazabilidad.
--
-- IMPORTANTE: el reparto es por LÍMITES FÍSICOS del canal, nunca para bajar del umbral de minimis del
-- destino. Fraccionar un pedido con ese fin es fraccionamiento artificial y está prohibido en la UE
-- (los envíos del mismo pedido al mismo destinatario se agregan). El umbral se sigue evaluando sobre el
-- pedido completo en CustomsValuationService.
CREATE TABLE IF NOT EXISTS order_shipment (
    id                uuid PRIMARY KEY,
    order_id          uuid        NOT NULL REFERENCES customer_order (id) ON DELETE CASCADE,
    sequence_no       integer     NOT NULL,              -- 1..N, orden estable para mostrar "Paquete 1 de N"
    carrier           varchar(64),
    product_code      varchar(50),                       -- canal del transportista usado para este bulto
    waybill_number    varchar(64),                       -- guía del transportista (identificador real)
    tracking_number   varchar(64),                       -- nº que ve el cliente (puede llegar más tarde)
    status            varchar(32),                       -- último estado conocido de ESTE bulto
    weight_grams      integer     NOT NULL DEFAULT 0,
    declared_value_cents integer  NOT NULL DEFAULT 0,
    label_url         text,
    estimated_delivery_at timestamptz,
    last_tracked_at   timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz,
    CONSTRAINT uq_order_shipment_seq UNIQUE (order_id, sequence_no)
);

CREATE INDEX IF NOT EXISTS idx_order_shipment_order ON order_shipment (order_id, sequence_no);
-- La guía es como el transportista identifica el envío en sus pushes y consultas: debe ser única y
-- buscable, pero admite nulos mientras el envío aún no se ha creado.
CREATE UNIQUE INDEX IF NOT EXISTS uq_order_shipment_waybill
    ON order_shipment (waybill_number) WHERE waybill_number IS NOT NULL;

-- Cada evento de trazabilidad pertenece a un bulto concreto. Se deja NULL para los eventos del pedido
-- que no son de un envío en particular (el "envío registrado" que escribe el sistema) y para todo el
-- histórico anterior a esta migración, que no se puede reasignar.
ALTER TABLE order_tracking_event
    ADD COLUMN IF NOT EXISTS shipment_id uuid REFERENCES order_shipment (id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS idx_order_tracking_event_shipment
    ON order_tracking_event (shipment_id, occurred_at);

COMMENT ON TABLE order_shipment IS
    'Bultos en los que se reparte un pedido. Uno por guía del transportista.';
COMMENT ON COLUMN order_shipment.sequence_no IS
    'Posición del bulto dentro del pedido (1..N), para mostrar "Paquete i de N" de forma estable.';
