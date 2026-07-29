--liquibase formatted sql

--changeset nexadrop:v91-fulfillment-retry splitStatements:false
-- Estado del intento de creación del envío en el transportista.
--
-- Sin esto, un envío que el carrier RECHAZA se reintenta cada 60 s indefinidamente y el único rastro es
-- una línea de log: el pedido se queda en FORWARDED sin guía y nadie se entera. Peor aún, no se distingue
-- un fallo transitorio (timeout del gateway, que se arregla solo) de uno permanente (el bulto excede los
-- límites del canal contratado, que no se va a arreglar reintentando).
--
-- Con estas columnas el scheduler puede espaciar los reintentos, rendirse ante un fallo definitivo y
-- dejar el motivo a la vista del admin en la ficha del pedido.
ALTER TABLE customer_order
    ADD COLUMN IF NOT EXISTS fulfillment_attempts        integer     NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS fulfillment_error           text,
    ADD COLUMN IF NOT EXISTS fulfillment_failed_at       timestamptz,
    ADD COLUMN IF NOT EXISTS fulfillment_next_attempt_at timestamptz;

COMMENT ON COLUMN customer_order.fulfillment_attempts IS
    'Intentos de creación del envío consumidos; se pone a 0 en cuanto la guía se crea.';
COMMENT ON COLUMN customer_order.fulfillment_error IS
    'Motivo del último fallo tal cual lo devuelve el transportista, para que el admin sepa qué corregir.';
COMMENT ON COLUMN customer_order.fulfillment_failed_at IS
    'Cuándo se dio por definitivo el fallo. No nulo = el scheduler ya no reintenta y hace falta acción manual.';
COMMENT ON COLUMN customer_order.fulfillment_next_attempt_at IS
    'No se reintenta antes de este instante (backoff exponencial entre fallos transitorios).';

-- Índice parcial para el listado de incidencias del admin: solo interesan los envíos rendidos, que son
-- unos pocos frente al total de pedidos.
CREATE INDEX IF NOT EXISTS idx_customer_order_fulfillment_failed
    ON customer_order (fulfillment_failed_at DESC)
    WHERE fulfillment_failed_at IS NOT NULL;
