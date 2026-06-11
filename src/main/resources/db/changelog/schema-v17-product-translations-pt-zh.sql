--liquibase formatted sql
--changeset nexadrop:v17-product-translations-pt-zh
-- DROP-473: hasta ahora el seed sólo persistía traducciones es/en por producto
-- y los usuarios pt/zh veían el storefront con texto inglés. Generamos las dos
-- filas que faltan para cada producto existente, reusando datos que ya están
-- en BD (product.title_zh para chino, product_translation.es para portugués
-- como base — el pipeline de traducción automática las refinará después).

-- ZH: usar product.title_zh ya presente; descripción copia de la fila ES.
INSERT INTO product_translation (id, product_id, language, title, short_description, description, provider, created_at, updated_at)
SELECT gen_random_uuid(), p.id, 'zh',
       p.title_zh,
       (SELECT pt_es.short_description FROM product_translation pt_es WHERE pt_es.product_id = p.id AND pt_es.language = 'es'),
       (SELECT pt_es.description       FROM product_translation pt_es WHERE pt_es.product_id = p.id AND pt_es.language = 'es'),
       'seed-backfill', now(), now()
FROM product p
WHERE NOT EXISTS (SELECT 1 FROM product_translation pt WHERE pt.product_id = p.id AND pt.language = 'zh');

-- PT: copia de la fila ES (texto comercial cercano al portugués; el pipeline
-- de traducción automática reemplazará estos provider='seed-backfill').
INSERT INTO product_translation (id, product_id, language, title, short_description, description, provider, created_at, updated_at)
SELECT gen_random_uuid(), p.id, 'pt',
       (SELECT pt_es.title             FROM product_translation pt_es WHERE pt_es.product_id = p.id AND pt_es.language = 'es'),
       (SELECT pt_es.short_description FROM product_translation pt_es WHERE pt_es.product_id = p.id AND pt_es.language = 'es'),
       (SELECT pt_es.description       FROM product_translation pt_es WHERE pt_es.product_id = p.id AND pt_es.language = 'es'),
       'seed-backfill', now(), now()
FROM product p
WHERE NOT EXISTS (SELECT 1 FROM product_translation pt WHERE pt.product_id = p.id AND pt.language = 'pt')
  AND EXISTS     (SELECT 1 FROM product_translation pt_es WHERE pt_es.product_id = p.id AND pt_es.language = 'es');
