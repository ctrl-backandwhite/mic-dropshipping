--liquibase formatted sql

--changeset nexadrop:v174-margen-interno-por-producto
--comment El IVA chino del producto pasa a ser lo que de verdad es: el margen interno, en porcentaje.
--
-- `iva_cny` nunca fue el IVA de China: el IVA chino es el 13 %, y aquí valía EXACTAMENTE el 50 % de
-- la base en los 263 productos del catálogo. Era un margen interno con nombre equivocado, guardado
-- como importe absoluto en yuanes, lo que obligaba a recalcularlo a mano cada vez que cambiaba el
-- coste del proveedor.
--
-- Guardarlo en porcentaje lo arregla solo: el margen sigue al coste sin que nadie lo toque, y lo que
-- se enseña en el desglose del panel deja de mentir sobre qué es ese dinero.
--
-- La columna es NULABLE a propósito. Un NOT NULL con DEFAULT no protege del nulo EXPLÍCITO que manda
-- la carga masiva, y esa trampa ya tumbó una carga entera una semana entera sin que nadie lo viera.
-- Nulo significa «usa el porcentaje por defecto», y de eso se encarga el código.
SET lock_timeout = '5s';

ALTER TABLE product ADD COLUMN IF NOT EXISTS margen_interno_pct numeric(6,3);

COMMENT ON COLUMN product.margen_interno_pct IS
  'Margen interno en porcentaje sobre el coste del proveedor. Nulo = el porcentaje por defecto.';

-- Se traslada lo que había, para que ningún precio se mueva: el importe que valía iva_cny es el mismo
-- que sale de aplicar este porcentaje sobre la base.
UPDATE product
   SET margen_interno_pct = round((iva_cny / base_price) * 100, 3)
 WHERE iva_cny IS NOT NULL
   AND base_price IS NOT NULL
   AND base_price > 0
   AND margen_interno_pct IS NULL;

--rollback ALTER TABLE product DROP COLUMN IF EXISTS margen_interno_pct;
