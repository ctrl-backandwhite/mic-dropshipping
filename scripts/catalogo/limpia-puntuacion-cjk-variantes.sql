-- Quita la puntuación china de los nombres de variante YA traducidos.
--
-- QUÉ ARREGLA: la traducción está bien —«Negro», «Azul claro»— pero viene envuelta en corchetes
-- chinos y con una coletilla del proveedor: 『 Negro 』, «Gris 〈♥〉2020». No son ideogramas, así que
-- la búsqueda de caracteres Han no los encuentra, y aun así el comprador los lee como un error.
--
-- Se aplica a los SIETE idiomas de una vez: corregir solo el español dejaría la misma variante bien
-- en una tienda y mal en las otras seis.
--
-- El `value_zh` NO se toca: es el texto original del proveedor y es la llave con la que se cruzan las
-- opciones de la variante. Cambiarlo dejaría la traducción sin encontrar y la variante saldría en
-- chino, que es justo lo contrario de lo que se busca.
BEGIN;

UPDATE variant_value_translation
SET value = btrim(regexp_replace(
        -- Primero la coletilla entera, que lleva el adorno pegado a un año: «〈♥〉2020».
        regexp_replace(value, '\s*〈[^〉]*〉\s*\d*', '', 'g'),
        -- Después los corchetes chinos, que solo envuelven.
        '[『』【】〖〗「」〈〉]', '', 'g'))
WHERE value ~ '[『』〈〉【】〖〗「」]';

COMMIT;

-- Lo que quede aquí es que se escapó algún signo nuevo.
SELECT count(*) AS pendientes, count(DISTINCT language) AS idiomas
FROM variant_value_translation
WHERE value ~ '[『』〈〉【】〖〗「」]';

SELECT language, value FROM variant_value_translation
WHERE variant_value_id IN (
    SELECT vv.id FROM variant_value vv
    JOIN variant_option vo ON vo.id = vv.option_id
    JOIN product p ON p.id = vo.product_id
    WHERE p.external_id IN ('808863525786', '864087224203'))
  AND language IN ('es', 'en')
ORDER BY language, value LIMIT 12;
