--liquibase formatted sql
--changeset nexadrop:v34-warehouse-seed-city-coherence
-- DROP-640: los almacenes seed WH-SEED-1..30 quedaron con país y ciudad
-- incoherentes (p.ej. US/Guangzhou, ES/Yiwu, DE/Hangzhou, IT/Miami, CN/Madrid).
-- La causa: el seed (infra/seed/seed50.sql) escogía country de un array de 10
-- elementos y city de un array de 8, ambos indexados por separado con i%len, por
-- lo que el emparejamiento país-ciudad era prácticamente aleatorio.
--
-- Esta migración reasigna SOLO la ciudad de cada WH-SEED para que pertenezca al
-- país de la fila (el country se mantiene tal cual). Se aplica un mapeo
-- país -> ciudad principal razonable. Los almacenes "reales" (CN-SHZ, ES-MAD, ...)
-- ya son coherentes y NO se tocan (sólo filas con code LIKE 'WH-SEED-%').
UPDATE warehouse SET city = CASE country
    WHEN 'CN' THEN 'Shenzhen'
    WHEN 'US' THEN 'Los Angeles'
    WHEN 'ES' THEN 'Madrid'
    WHEN 'DE' THEN 'Berlin'
    WHEN 'FR' THEN 'Paris'
    WHEN 'GB' THEN 'London'
    WHEN 'IT' THEN 'Milan'
    WHEN 'MX' THEN 'Mexico City'
    WHEN 'BR' THEN 'Sao Paulo'
    WHEN 'NL' THEN 'Rotterdam'
    ELSE city
END
WHERE code LIKE 'WH-SEED-%';
