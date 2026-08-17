--liquibase formatted sql

--changeset nexadrop:v141-limite-aceptacion-carrier splitStatements:false
--
-- Un pedido de 150,00 EUR exactos se cobra y el transportista lo rechaza.
--
-- Hasta ahora solo existía UN límite, el fiscal, y se usaba para las dos cosas. Pero son distintos:
--
--   · Límite FISCAL (Reglamento UE 2023/2411 art. 2): el régimen de bajo valor aplica cuando el valor
--     intrínseco «no excede» 150 EUR. La comparación es estricta —150,00 EUR exactos SÍ están dentro y
--     pagan sus 3 EUR por partida—, y así está probado en CustomsRegulationIT con las citas del
--     Reglamento. Eso NO se toca.
--
--   · Límite de ACEPTACIÓN del transportista: el contrato de YunExpress dice
--     «不接受等于和大于150欧元或155美金的包裹» — no acepta paquetes IGUALES O MAYORES a 150 EUR o a
--     155 USD. Es una restricción comercial suya, más estricta que la norma, y no estaba modelada.
--
-- El hueco era el borde exacto: 150,00 EUR no excede la franquicia (correcto), no marca umbral superado,
-- no lo bloquea la política BLOCK... y el transportista lo rechaza igualmente, dejando un pedido cobrado
-- que no se puede despachar. Y hay un segundo tope en dólares que nadie miraba: si el euro sube de
-- 1,033 USD, el límite que manda es el de 155 USD y no el de 150 EUR.
--
-- Se añaden DOS topes por país porque el contrato impone dos a la vez y gana el menor. 0 = sin límite,
-- igual que en el resto de la tabla. Se siembran los 27 de la UE y Reino Unido, que son los destinos
-- con la restricción documentada.

ALTER TABLE country_customs_rule
    ADD COLUMN IF NOT EXISTS carrier_max_amount       NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS carrier_max_currency     VARCHAR(3)    NOT NULL DEFAULT 'USD',
    ADD COLUMN IF NOT EXISTS carrier_max_alt_amount   NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS carrier_max_alt_currency VARCHAR(3)    NOT NULL DEFAULT 'USD';

COMMENT ON COLUMN country_customs_rule.carrier_max_amount IS
    'Valor a partir del cual (>=) el transportista NO acepta el envío. 0 = sin límite.';
COMMENT ON COLUMN country_customs_rule.carrier_max_alt_amount IS
    'Segundo tope simultáneo del transportista en otra divisa; gana el menor de los dos.';

-- UE-27: 150 EUR o 155 USD, lo que resulte menor al cambio del día.
UPDATE country_customs_rule
   SET carrier_max_amount = 150.00, carrier_max_currency = 'EUR',
       carrier_max_alt_amount = 155.00, carrier_max_alt_currency = 'USD',
       updated_at = NOW(), updated_by = 'v141-limite-aceptacion-carrier'
 WHERE country_code IN ('AT','BE','BG','HR','CY','CZ','DK','EE','FI','FR','DE','GR','HU','IE','IT',
                        'LV','LT','LU','MT','NL','PL','PT','RO','SK','SI','ES','SE');

-- Reino Unido: el contrato añade el tope en libras (135 GBP / 155 USD / 150 EUR). Se guardan los dos
-- más restrictivos de los tres.
UPDATE country_customs_rule
   SET carrier_max_amount = 135.00, carrier_max_currency = 'GBP',
       carrier_max_alt_amount = 155.00, carrier_max_alt_currency = 'USD',
       updated_at = NOW(), updated_by = 'v141-limite-aceptacion-carrier'
 WHERE country_code = 'GB';

--changeset nexadrop:v141-002-prepago-iva-por-pais splitStatements:false
--
-- Marca de qué destinos llevan el IVA prepagado por el transportista.
--
-- El servicio que se le pide al crear el envío (云途预缴) liquida el IVA de la UE con el IOSS de
-- YunExpress. Pero los 86 destinos activos están en DDP, así que condicionar el envío de ese servicio
-- solo al modo fiscal lo pediría también para Estados Unidos, Brasil o Australia —donde ese régimen no
-- existe— y el alta del envío fallaría. Se marca por país, como el resto de reglas aduaneras.

ALTER TABLE country_customs_rule
    ADD COLUMN IF NOT EXISTS carrier_prepays_vat BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN country_customs_rule.carrier_prepays_vat IS
    'El transportista liquida el IVA del destino con su propio número fiscal (IOSS en la UE).';

UPDATE country_customs_rule
   SET carrier_prepays_vat = TRUE, updated_at = NOW(), updated_by = 'v141-002-prepago-iva-por-pais'
 WHERE country_code IN ('AT','BE','BG','HR','CY','CZ','DK','EE','FI','FR','DE','GR','HU','IE','IT',
                        'LV','LT','LU','MT','NL','PL','PT','RO','SK','SI','ES','SE');
