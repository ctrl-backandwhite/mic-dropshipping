--liquibase formatted sql

--changeset nexadrop:v175-tarifa-del-proveedor
--comment Lo que el proveedor cobra DE VERDAD por el porte nacional chino: la primera unidad y cada siguiente.
--
-- `shipping_cny` (v173) es el importe de UNA unidad y sirve para una unidad. El proveedor no cobra
-- por peso: cobra una primera unidad y un incremento por cada siguiente. Medido sobre diez puntos de
-- una ficha real de 1688 el 25-sep-2026, la recta sale exacta: ¥8 la primera y ¥3 cada siguiente.
--
-- Con sólo el importe de una unidad, un pedido de cinco calculaba 5 × 8 = ¥40 donde el proveedor
-- cobra 8 + 4 × 3 = ¥20. El error es del doble y siempre en la misma dirección: se cotiza de más y
-- se pierde la venta.
--
-- Nullable a propósito, y por el mismo motivo que la v173: sólo el 67% de las fichas se puede sondar
-- —la ficha tiene que enseñar una caja de cantidad y recalcular el porte— y los 7.649 productos
-- cargados antes de que la sonda existiera no la traen. Un DEFAULT 0 diría «este proveedor no cobra
-- porte», que es falso para todos ellos.
--
-- Sólo en `product_variant`: la tarifa es del proveedor, pero quien la usa para cotizar es el SKU.
-- El importador la copia del producto a cada variante que no declare la suya, así que una columna en
-- `product` no la leería nadie.
ALTER TABLE product_variant ADD COLUMN IF NOT EXISTS supplier_ship_first_cny NUMERIC(12,4);
ALTER TABLE product_variant ADD COLUMN IF NOT EXISTS supplier_ship_extra_cny NUMERIC(12,4);
