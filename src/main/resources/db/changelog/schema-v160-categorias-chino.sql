--liquibase formatted sql

--changeset nexadrop:v160-categorias-chino
--comment Las categorias solo estaban traducidas a siete idiomas: faltaba el chino entero, aunque el
--nombre ya estaba en category.name_zh. Los productos si tienen los ocho, asi que un catalogo en chino
--mostraba los productos traducidos colgando de categorias en otro idioma. Se rellena desde name_zh,
--que esta poblado en las 1966 categorias. ON CONFLICT para que sea repetible y no pise lo ya escrito.

INSERT INTO category_translation (id, category_id, language, name, created_at, updated_at, created_by, updated_by)
SELECT gen_random_uuid(), c.id, 'zh', btrim(c.name_zh), now(), now(), 'v160-categorias-chino', 'v160-categorias-chino'
FROM category c
WHERE c.name_zh IS NOT NULL AND btrim(c.name_zh) <> ''
ON CONFLICT (category_id, language) DO NOTHING;

--changeset nexadrop:v160-moda-cal-31-traducciones
--comment La categoria de botas de hombre se cargo sin ninguna traduccion, asi que se quedaba sin nombre
--en los ocho idiomas. Los nombres siguen el estilo de sus hermanas de calzado (moda-cal-14 es la misma
--prenda para mujer): en espanol "para hombre", en ingles "Men's", en aleman "Herren-", en neerlandes
--"Heren". El chino ya lo pone el cambio anterior desde name_zh.

INSERT INTO category_translation (id, category_id, language, name, created_at, updated_at, created_by, updated_by)
SELECT gen_random_uuid(), c.id, v.language, v.name, now(), now(), 'v160-categorias-chino', 'v160-categorias-chino'
FROM category c
CROSS JOIN (VALUES
    ('es', 'Botas para hombre'),
    ('en', 'Men''s Boots'),
    ('pt', 'Botas Masculinas'),
    ('fr', 'Bottes homme'),
    ('de', 'Herren-Stiefel'),
    ('it', 'Stivali da uomo'),
    ('nl', 'Heren laarzen')
) AS v(language, name)
WHERE c.slug = 'moda-cal-31'
ON CONFLICT (category_id, language) DO NOTHING;
