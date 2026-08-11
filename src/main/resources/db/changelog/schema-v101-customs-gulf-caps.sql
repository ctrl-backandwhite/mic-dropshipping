--liquibase formatted sql

--changeset nexadrop:v101-customs-gulf-caps splitStatements:false
-- Tope de importación para los países del Golfo que la línea de YunExpress NO acepta por encima de un
-- valor máximo. Cierra el mismo hueco que v100 (UE/UK) pero para estos destinos, que estaban sin umbral
-- (de_minimis = 0) y por tanto NO se bloqueaban.
--
-- A diferencia de la UE (donde el umbral es la franquicia de aranceles IOSS), aquí el valor es el
-- MÁXIMO que la línea admite declarado: por encima, el paquete no se acepta. Se reutiliza el campo
-- de_minimis con política BLOCK porque el efecto buscado es el mismo: impedir en el checkout un pedido
-- que después no se podría enviar. Importes en USD, tal como los publica el documento oficial
-- (cotización 2026-08-10, notas de la línea de ropa 云途全球服装专线):
--   Kuwait  (KW): máx 300 USD
--   Catar   (QA): máx 244 USD
--   Jordania(JO): máx 280 USD
--   Baréin  (BH): máx 259 USD
-- Omán (OM) NO lleva tope: el documento no publica un máximo para ese destino, así que se deja sin
-- umbral (no se bloquea). Emiratos (AE) ya tenía su tope de 270 USD desde antes.
UPDATE country_customs_rule SET de_minimis_amount = 300, de_minimis_currency = 'USD',
       over_threshold_policy = 'BLOCK' WHERE country_code = 'KW';
UPDATE country_customs_rule SET de_minimis_amount = 244, de_minimis_currency = 'USD',
       over_threshold_policy = 'BLOCK' WHERE country_code = 'QA';
UPDATE country_customs_rule SET de_minimis_amount = 280, de_minimis_currency = 'USD',
       over_threshold_policy = 'BLOCK' WHERE country_code = 'JO';
UPDATE country_customs_rule SET de_minimis_amount = 259, de_minimis_currency = 'USD',
       over_threshold_policy = 'BLOCK' WHERE country_code = 'BH';
