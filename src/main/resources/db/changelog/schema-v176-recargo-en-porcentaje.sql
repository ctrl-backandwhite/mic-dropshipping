--liquibase formatted sql

--changeset nexadrop:v176-recargo-en-porcentaje
--comment El recargo pasa de importe fijo en yuanes a porcentaje sobre el coste, como el margen interno.
--
-- Mismo problema que tenía el margen interno antes de v174: guardado como importe absoluto, no seguía
-- al coste del proveedor. Si el coste subía, el recargo se quedaba donde estaba y había que rehacerlo
-- a mano producto a producto, o se desfasaba en silencio.
--
-- A diferencia del margen interno —que valía el 50 % exacto en los 263 productos— aquí el porcentaje
-- equivalente NO es uniforme: medido sobre preproducción el 25-sep-2026, los 257 productos con
-- recargo van del 59 % al 250 % de la base. Por eso el traslado es producto a producto y no una
-- constante: poner un porcentaje único movería el precio de casi todos.
--
-- Nulables a propósito, y aquí nulo y cero son cosas DISTINTAS: en el tramo, nulo significa «este
-- tramo no tiene recargo propio, hereda el del producto» y cero es un recargo de cero de verdad.
-- Confundirlos pondría a cero los 3.240 tramos que hoy heredan.
SET lock_timeout = '5s';

ALTER TABLE product ADD COLUMN IF NOT EXISTS surcharge_pct numeric(6,3);
ALTER TABLE product_price_tier ADD COLUMN IF NOT EXISTS surcharge_pct numeric(6,3);

COMMENT ON COLUMN product.surcharge_pct IS
  'Recargo en porcentaje sobre el coste del proveedor. Nulo o cero = sin recargo.';
COMMENT ON COLUMN product_price_tier.surcharge_pct IS
  'Recargo propio del tramo, en porcentaje. NULO = hereda el del producto; CERO = recargo de cero.';

-- Se traslada lo que había para que ningún precio se mueva.
UPDATE product
   SET surcharge_pct = round((surcharge_cny / base_price) * 100, 3)
 WHERE surcharge_cny IS NOT NULL AND surcharge_cny <> 0
   AND base_price IS NOT NULL AND base_price > 0
   AND surcharge_pct IS NULL;

-- El tramo se traslada contra el precio unitario DEL TRAMO, no contra la base del producto: es el
-- importe sobre el que se va a aplicar el porcentaje a partir de ahora.
UPDATE product_price_tier t
   SET surcharge_pct = round((t.surcharge_cny / t.unit_price) * 100, 3)
 WHERE t.surcharge_cny IS NOT NULL AND t.surcharge_cny <> 0
   AND t.unit_price IS NOT NULL AND t.unit_price > 0
   AND t.surcharge_pct IS NULL;

-- Un recargo escrito a cero era un cero de verdad y lo sigue siendo.
UPDATE product SET surcharge_pct = 0 WHERE surcharge_cny = 0 AND surcharge_pct IS NULL;
UPDATE product_price_tier SET surcharge_pct = 0 WHERE surcharge_cny = 0 AND surcharge_pct IS NULL;

--rollback ALTER TABLE product DROP COLUMN IF EXISTS surcharge_pct;
--rollback ALTER TABLE product_price_tier DROP COLUMN IF EXISTS surcharge_pct;
