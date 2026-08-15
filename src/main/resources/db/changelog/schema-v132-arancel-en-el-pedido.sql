--liquibase formatted sql

--changeset nexadrop:v132-arancel-en-el-pedido
--
-- El arancel de la Unión iba SUMADO dentro de `shipping_cents` y no se guardaba en ninguna parte.
--
-- Consecuencia: la factura no podía desglosarlo. El checkout sí lo muestra como línea propia —«Aranceles
-- UE · 3,46 €»— pero la factura enseñaba un envío inflado sin explicar por qué, y quien comparase ambos
-- documentos vería dos importes de envío distintos para el mismo pedido. En una factura, un cargo público
-- que el vendedor recauda y entrega a la aduana tiene que aparecer identificado: no es parte del precio
-- del transporte.
--
-- Se guarda al crear el pedido, junto al resto del desglose, para que la factura de dentro de un año
-- refleje lo que se cobró ENTONCES y no lo que la tarifa diga en el momento de reimprimirla.
--
-- Los pedidos anteriores se quedan a cero: no se puede reconstruir cuánto arancel llevaba cada uno, y
-- repartirlo a posteriori sería inventar un dato fiscal. Sus facturas siguen mostrando el envío completo,
-- que es exactamente lo que se les cobró.
--
ALTER TABLE customer_order
    ADD COLUMN IF NOT EXISTS customs_duty_cents integer NOT NULL DEFAULT 0;

COMMENT ON COLUMN customer_order.customs_duty_cents IS
    'Derecho de aduana de la UE cobrado en este pedido (céntimos USD), ya incluido en shipping_cents. Se '
    'guarda aparte para poder desglosarlo en la factura; 0 en los pedidos anteriores a la v132.';
