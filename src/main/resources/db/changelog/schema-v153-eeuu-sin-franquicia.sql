--liquibase formatted sql

--changeset nexadrop:v153-de-minimis-applies splitStatements:false
-- «Sin franquicia» y «franquicia sin averiguar» dejan de ser el mismo dato.
--
-- Hasta ahora las dos cosas se escribían igual, `de_minimis_amount = 0`, y el motor las leía como la
-- segunda: `CustomsValuationService.exceedsDeMinimis` exige un umbral MAYOR que cero para considerar un
-- pedido «por encima», así que un cero nunca se superaba y el destino no recibía ni arancel ni recargo ni
-- bloqueo. La intención al sembrar Estados Unidos era la contraria —el propio comentario de la v88 dice
-- «se siembra 0 (declara y paga siempre) por ser el criterio conservador»—, y ganó la lectura del motor.
-- El resultado es que el destino que más protección necesitaba era justo el que no tenía ninguna.
--
-- Esta columna hace explícita la diferencia en lugar de reinterpretar el cero, que cambiaría en silencio
-- el comportamiento de los otros 33 países que lo tienen por falta de dato, no por ausencia de franquicia.
ALTER TABLE country_customs_rule
  ADD COLUMN IF NOT EXISTS de_minimis_applies BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN country_customs_rule.de_minimis_applies IS
  'FALSE = el destino NO tiene franquicia: cualquier pedido está por encima del umbral y le aplica la '
  'política de over_threshold_policy. TRUE (por defecto) = se evalúa de_minimis_amount como hasta ahora.';

--changeset nexadrop:v153-eeuu-bloqueado splitStatements:false
-- Estados Unidos suspendió su franquicia de 800 USD (Section 321): primero para el origen chino en
-- febrero de 2025 y después, el 24-jun-2026, de forma indefinida y para TODOS los países. Desde entonces
-- cualquier envío exige entrada formal o informal con aranceles, y los envíos postales necesitan además
-- fianza aduanera y clasificación HTSUS a 10 dígitos —que este catálogo no tiene, porque trabaja con HS de
-- 6 para el IOSS—. La eliminación pasa a ser de ley el 1-jul-2027 (One Big Beautiful Bill Act).
--
-- Se bloquea en vez de cobrar un arancel estimado porque el arancel real depende del HTSUS de cada
-- producto (Section 301, del 7,5 % al 25 %, más el arancel base), y porque está sin confirmar que la línea
-- de e-commerce simplificado contratada pueda siquiera despachar allí bajo el régimen nuevo: cobrar un
-- recargo por un envío que el transportista no admite sería cobrar por algo que no se puede entregar.
--
-- Es la misma decisión que ya se tomó para la UE por encima de 150 EUR, y por el mismo motivo. Mientras
-- siga así NO se vende a Estados Unidos; para reabrirlo basta con poner de_minimis_applies = TRUE desde
-- /api/admin/customs-rules cuando el transportista facilite tarifa de despacho formal.
--
-- Puerto Rico (PR) va incluido: es territorio aduanero estadounidense, mismo régimen y mismo problema.
UPDATE country_customs_rule
   SET de_minimis_applies   = FALSE,
       over_threshold_policy = 'BLOCK',
       updated_at            = now(),
       updated_by            = 'v153-eeuu-sin-franquicia'
 WHERE country_code IN ('US', 'PR');
