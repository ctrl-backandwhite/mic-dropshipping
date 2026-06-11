--liquibase formatted sql
--changeset nexadrop:v23-product-translations-backfill
-- DROP-537/540/541: el QA seguía viendo productos en chino (100W编织数据线…) en
-- detalle de orden, POD blanks y intelligence. Causa: muchos productos en BD
-- (importados antes de DROP-473) no tienen filas en product_translation y el
-- backend caía al title_zh. Backfilleamos ES y EN para CADA producto sin
-- traducción usando templates realistas por categoría — el contenido luego
-- lo refinará el pipeline de traducción automática, pero al menos no expone
-- caracteres chinos al usuario europeo.

INSERT INTO product_translation (id, product_id, language, title, short_description, description, provider, created_at, updated_at)
SELECT gen_random_uuid(), p.id, 'es',
       CASE c.slug
         WHEN 'consumer-electronics' THEN 'Dispositivo electrónico ' || COALESCE(p.brand, 'NX') || ' ' || SUBSTR(p.external_id, GREATEST(1, LENGTH(p.external_id)-3))
         WHEN 'fashion-apparel'      THEN 'Prenda de moda ' || COALESCE(p.brand, 'NX') || ' ref. ' || SUBSTR(p.external_id, GREATEST(1, LENGTH(p.external_id)-3))
         WHEN 'home-kitchen'         THEN 'Artículo de hogar y cocina ' || COALESCE(p.brand, 'NX') || ' ref. ' || SUBSTR(p.external_id, GREATEST(1, LENGTH(p.external_id)-3))
         WHEN 'beauty-personal-care' THEN 'Producto de belleza ' || COALESCE(p.brand, 'NX') || ' ref. ' || SUBSTR(p.external_id, GREATEST(1, LENGTH(p.external_id)-3))
         WHEN 'sports-outdoors'      THEN 'Equipamiento deportivo ' || COALESCE(p.brand, 'NX') || ' ref. ' || SUBSTR(p.external_id, GREATEST(1, LENGTH(p.external_id)-3))
         WHEN 'toys-gifts'           THEN 'Juguete / regalo ' || COALESCE(p.brand, 'NX') || ' ref. ' || SUBSTR(p.external_id, GREATEST(1, LENGTH(p.external_id)-3))
         WHEN 'auto-parts'           THEN 'Repuesto / accesorio auto ' || COALESCE(p.brand, 'NX') || ' ref. ' || SUBSTR(p.external_id, GREATEST(1, LENGTH(p.external_id)-3))
         WHEN 'office-supplies'      THEN 'Material de oficina ' || COALESCE(p.brand, 'NX') || ' ref. ' || SUBSTR(p.external_id, GREATEST(1, LENGTH(p.external_id)-3))
         WHEN 'pet-supplies'         THEN 'Producto para mascotas ' || COALESCE(p.brand, 'NX') || ' ref. ' || SUBSTR(p.external_id, GREATEST(1, LENGTH(p.external_id)-3))
         WHEN 'tools-hardware'       THEN 'Herramienta ' || COALESCE(p.brand, 'NX') || ' ref. ' || SUBSTR(p.external_id, GREATEST(1, LENGTH(p.external_id)-3))
         WHEN 'jewelry-watches'      THEN 'Joya / reloj ' || COALESCE(p.brand, 'NX') || ' ref. ' || SUBSTR(p.external_id, GREATEST(1, LENGTH(p.external_id)-3))
         WHEN 'garden-outdoor'       THEN 'Artículo de jardín ' || COALESCE(p.brand, 'NX') || ' ref. ' || SUBSTR(p.external_id, GREATEST(1, LENGTH(p.external_id)-3))
         WHEN 'baby-maternity'       THEN 'Producto bebé / maternidad ' || COALESCE(p.brand, 'NX') || ' ref. ' || SUBSTR(p.external_id, GREATEST(1, LENGTH(p.external_id)-3))
         WHEN 'lighting'             THEN 'Iluminación ' || COALESCE(p.brand, 'NX') || ' ref. ' || SUBSTR(p.external_id, GREATEST(1, LENGTH(p.external_id)-3))
         ELSE 'Producto ' || COALESCE(p.brand, 'NX036') || ' ref. ' || SUBSTR(p.external_id, GREATEST(1, LENGTH(p.external_id)-3))
       END,
       'Importado de 1688. Pendiente de traducción profesional.',
       'Importado de 1688 ('|| p.external_id ||'). Pendiente de traducción profesional. Contacta con soporte si necesitas la ficha técnica completa antes de pedir.',
       'seed-backfill', now(), now()
FROM product p
LEFT JOIN category c ON c.id = p.category_id
WHERE NOT EXISTS (SELECT 1 FROM product_translation pt WHERE pt.product_id = p.id AND pt.language = 'es');

INSERT INTO product_translation (id, product_id, language, title, short_description, description, provider, created_at, updated_at)
SELECT gen_random_uuid(), p.id, 'en',
       CASE c.slug
         WHEN 'consumer-electronics' THEN 'Electronic device ' || COALESCE(p.brand, 'NX') || ' ' || SUBSTR(p.external_id, GREATEST(1, LENGTH(p.external_id)-3))
         WHEN 'fashion-apparel'      THEN 'Fashion item ' || COALESCE(p.brand, 'NX') || ' ref. ' || SUBSTR(p.external_id, GREATEST(1, LENGTH(p.external_id)-3))
         WHEN 'home-kitchen'         THEN 'Home & kitchen item ' || COALESCE(p.brand, 'NX') || ' ref. ' || SUBSTR(p.external_id, GREATEST(1, LENGTH(p.external_id)-3))
         WHEN 'beauty-personal-care' THEN 'Beauty product ' || COALESCE(p.brand, 'NX') || ' ref. ' || SUBSTR(p.external_id, GREATEST(1, LENGTH(p.external_id)-3))
         WHEN 'sports-outdoors'      THEN 'Sports gear ' || COALESCE(p.brand, 'NX') || ' ref. ' || SUBSTR(p.external_id, GREATEST(1, LENGTH(p.external_id)-3))
         WHEN 'toys-gifts'           THEN 'Toy / gift ' || COALESCE(p.brand, 'NX') || ' ref. ' || SUBSTR(p.external_id, GREATEST(1, LENGTH(p.external_id)-3))
         WHEN 'auto-parts'           THEN 'Auto part / accessory ' || COALESCE(p.brand, 'NX') || ' ref. ' || SUBSTR(p.external_id, GREATEST(1, LENGTH(p.external_id)-3))
         WHEN 'office-supplies'      THEN 'Office supply ' || COALESCE(p.brand, 'NX') || ' ref. ' || SUBSTR(p.external_id, GREATEST(1, LENGTH(p.external_id)-3))
         WHEN 'pet-supplies'         THEN 'Pet product ' || COALESCE(p.brand, 'NX') || ' ref. ' || SUBSTR(p.external_id, GREATEST(1, LENGTH(p.external_id)-3))
         WHEN 'tools-hardware'       THEN 'Tool ' || COALESCE(p.brand, 'NX') || ' ref. ' || SUBSTR(p.external_id, GREATEST(1, LENGTH(p.external_id)-3))
         WHEN 'jewelry-watches'      THEN 'Jewelry / watch ' || COALESCE(p.brand, 'NX') || ' ref. ' || SUBSTR(p.external_id, GREATEST(1, LENGTH(p.external_id)-3))
         WHEN 'garden-outdoor'       THEN 'Garden item ' || COALESCE(p.brand, 'NX') || ' ref. ' || SUBSTR(p.external_id, GREATEST(1, LENGTH(p.external_id)-3))
         WHEN 'baby-maternity'       THEN 'Baby / maternity item ' || COALESCE(p.brand, 'NX') || ' ref. ' || SUBSTR(p.external_id, GREATEST(1, LENGTH(p.external_id)-3))
         WHEN 'lighting'             THEN 'Lighting ' || COALESCE(p.brand, 'NX') || ' ref. ' || SUBSTR(p.external_id, GREATEST(1, LENGTH(p.external_id)-3))
         ELSE 'Product ' || COALESCE(p.brand, 'NX036') || ' ref. ' || SUBSTR(p.external_id, GREATEST(1, LENGTH(p.external_id)-3))
       END,
       'Imported from 1688. Pending professional translation.',
       'Imported from 1688 ('|| p.external_id ||'). Pending professional translation. Contact support if you need the full datasheet before placing a bulk order.',
       'seed-backfill', now(), now()
FROM product p
LEFT JOIN category c ON c.id = p.category_id
WHERE NOT EXISTS (SELECT 1 FROM product_translation pt WHERE pt.product_id = p.id AND pt.language = 'en');

-- También backfill PT si falta (DROP-465 ya lo hizo pero sólo para los que tenían ES; ahora cubrimos los nuevos).
INSERT INTO product_translation (id, product_id, language, title, short_description, description, provider, created_at, updated_at)
SELECT gen_random_uuid(), p.id, 'pt',
       es.title, es.short_description, es.description, 'seed-backfill-pt', now(), now()
FROM product p
JOIN product_translation es ON es.product_id = p.id AND es.language = 'es'
WHERE NOT EXISTS (SELECT 1 FROM product_translation pt WHERE pt.product_id = p.id AND pt.language = 'pt');

-- Y ZH a partir del title_zh (que sí existe en product) para que /zh muestre el chino correcto.
INSERT INTO product_translation (id, product_id, language, title, short_description, description, provider, created_at, updated_at)
SELECT gen_random_uuid(), p.id, 'zh',
       COALESCE(p.title_zh, 'Producto'), p.short_description_zh, p.description_zh, 'seed-backfill-zh', now(), now()
FROM product p
WHERE NOT EXISTS (SELECT 1 FROM product_translation pt WHERE pt.product_id = p.id AND pt.language = 'zh');
