--liquibase formatted sql
--changeset nexadrop:v36-adtrend-headlines-latin splitStatements:false endDelimiter:;
-- DROP-642 (B): los titulares de "Tendencias de anuncios" (ad_trend.headline) se
-- generaron incrustando el nombre del producto en chino (product.title_zh), p.ej.
--   "Cómo escalar 复古油蜡帆布托特包 手工制 con $50/día de ads".
-- El seed (DemoOperationsSeedRunner.seedAdTrends) ya se corrigió para usar el título
-- traducido; aquí reparamos los datos ya persistidos.
--
-- Estrategia: el título chino aparece como subcadena exacta dentro del headline, así
-- que sustituimos esa subcadena por la traducción latina del producto (ES preferida,
-- EN como fallback). Solo tocamos filas cuyo headline contiene CJK y cuyo producto
-- tiene una traducción latina disponible, de modo que el cambio es idempotente y
-- seguro de re-ejecutar.
DO $$
DECLARE
    r RECORD;
    latin_title text;
BEGIN
    FOR r IN
        SELECT a.id AS trend_id, p.title_zh AS zh
        FROM ad_trend a
        JOIN product p ON p.slug = a.product_slug
        WHERE a.headline ~ '[一-鿿]'
          AND p.title_zh IS NOT NULL
          AND position(p.title_zh IN a.headline) > 0
    LOOP
        SELECT t.title INTO latin_title
        FROM product_translation t
        JOIN product p ON p.id = t.product_id
        JOIN ad_trend a ON a.product_slug = p.slug
        WHERE a.id = r.trend_id
          AND t.language IN ('es', 'en')
          AND t.title IS NOT NULL
          AND t.title <> ''
          AND t.title !~ '[一-鿿]'
        ORDER BY CASE t.language WHEN 'es' THEN 0 WHEN 'en' THEN 1 ELSE 2 END
        LIMIT 1;

        IF latin_title IS NOT NULL THEN
            UPDATE ad_trend
            SET headline = replace(headline, r.zh, latin_title)
            WHERE id = r.trend_id;
        END IF;
    END LOOP;
END $$;
