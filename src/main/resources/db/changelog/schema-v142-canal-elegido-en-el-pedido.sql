--liquibase formatted sql

--changeset nexadrop:v142-canal-elegido-en-el-pedido splitStatements:false
--
-- El pedido no guardaba por qué canal se cotizó su envío.
--
-- Hasta ahora daba igual: el canal se resolvía al despachar, tomando el fijado en configuración o el más
-- barato del momento. Pero en cuanto el cliente elige forma de envío en el checkout —«5-8 días por 8,20»
-- frente a «6-10 días por 7,90»— hay que emitir la guía por ESE canal y no por otro. Si se recalcula al
-- despachar, se cobra una cosa y se envía otra: la tarifa cambia entre el pedido y el despacho, y el
-- plazo prometido deja de ser el del envío real.
--
-- Vacío significa «no eligió» y se mantiene el comportamiento anterior, así que los pedidos ya existentes
-- siguen despachándose igual.

ALTER TABLE customer_order
    ADD COLUMN IF NOT EXISTS shipping_channel_code VARCHAR(32);

COMMENT ON COLUMN customer_order.shipping_channel_code IS
    'Canal del transportista con el que se cotizó el envío que eligió el cliente. Vacío = sin elección.';
