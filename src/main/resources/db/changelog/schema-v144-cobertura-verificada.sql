--liquibase formatted sql

--changeset nexadrop:v144-cobertura-verificada splitStatements:false
--
-- La cobertura de destinos no coincidía con lo que el transportista cotiza de verdad.
--
-- La v130 fijó la lista a partir de la red publicada por YunExpress, porque entonces su API
-- de pruebas no respondía. Desde el 17-ago-2026 hay credenciales de PRODUCCIÓN, así que la
-- lista se puede contrastar con la fuente buena: se preguntó `/v1/price-trial/get` país por
-- país con un bulto de 0,5 kg de mercancía normal, que es lo que factura la cuenta
-- CNHC459832. Un destino que no devuelve ni una tarifa no se puede servir: al comprarlo, el
-- checkout caería a la tabla de zonas y prometería un envío que nadie puede despachar.
--
-- Criterio: se habilita el destino que cotiza al menos un canal y se deshabilita el que no
-- cotiza ninguno. No se dan de alta destinos nuevos: eso pide tarifa base y plazo de
-- respaldo, que es decisión de negocio y no de un seed.
--
-- Resultado: 45 destinos se habilitan, 0 se deshabilitan y
-- 45 ya estaban bien.

-- Destinos que SÍ cotizan y estaban apagados.
UPDATE cainiao_shipping_zone SET enabled = TRUE, updated_at = NOW(),
       updated_by = 'v144-cobertura-verificada'
 WHERE country_code IN ('AL', 'BA', 'BB', 'BH', 'BO', 'BS', 'BZ', 'CR', 'DO', 'EC', 'GT', 'GY', 'HN', 'ID', 'JM', 'JO', 'JP', 'KR', 'KW', 'LB', 'MC', 'MD', 'ME', 'MK', 'MY', 'NI', 'OM', 'PA', 'PE', 'PG', 'PH', 'PY', 'QA', 'RS', 'SG', 'SR', 'SV', 'TH', 'TR', 'TT', 'UA', 'UY', 'VE', 'VN', 'ZA');

