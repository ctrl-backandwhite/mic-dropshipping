--liquibase formatted sql

--changeset nexadrop:v147-transportista-del-pedido splitStatements:false
--
-- Qué transportista lleva cada pedido.
--
-- Hasta hoy solo había uno, así que bastaba con guardar el canal (`shipping_channel_code`). Desde que
-- CJ Dropshipping convive con YunExpress, el código por sí solo no dice de quién es: `FZZXR` y
-- `1868922929754472449` no se distinguen mirándolos, y pedirle la guía al transportista equivocado
-- significa cobrar un porte y pagar otro.
--
-- Los pedidos que ya existen son todos de YunExpress y así se dejan escrito: si se quedaran vacíos,
-- despachar uno antiguo obligaría a adivinar.
ALTER TABLE customer_order ADD COLUMN IF NOT EXISTS shipping_carrier VARCHAR(32);
UPDATE customer_order SET shipping_carrier = 'YUNEXPRESS' WHERE shipping_carrier IS NULL;

-- Y lo mismo en la guía ya emitida, para poder mirar un envío antiguo y saber quién lo llevó.
ALTER TABLE order_shipment ADD COLUMN IF NOT EXISTS carrier_name VARCHAR(32);
UPDATE order_shipment SET carrier_name = 'YUNEXPRESS' WHERE carrier_name IS NULL;

-- Y cómo llama el transportista a esa línea. CJ pide el NOMBRE («CJPacket Ordinary») para emitir la
-- guía, no el identificador; guardarlo al cobrar evita que el despacho tenga que volver a cotizar horas
-- después solo para cruzar las dos caras de la misma opción —una llamada de más contra una API limitada
-- a una petición por segundo— y evita quedarse sin despachar si para entonces esa línea ya no se ofrece.
ALTER TABLE customer_order ADD COLUMN IF NOT EXISTS shipping_channel_name VARCHAR(96);
