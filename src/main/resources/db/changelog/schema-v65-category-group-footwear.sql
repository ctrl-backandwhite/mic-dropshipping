--liquibase formatted sql

--changeset nexadrop:v65-001 splitStatements:true endDelimiter:;
--comment: Grupo de categorías: una colección de categorías que comparten una regla de margen (scope CATEGORY_GROUP). Permite aplicar UN solo margen a varias categorías (p. ej. todo el calzado).
CREATE TABLE IF NOT EXISTS category_group (
    id          UUID PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    description VARCHAR(300),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  VARCHAR(255),
    updated_by  VARCHAR(255)
);
CREATE TABLE IF NOT EXISTS category_group_member (
    group_id    UUID NOT NULL,
    category_id UUID NOT NULL,
    PRIMARY KEY (group_id, category_id)
);
CREATE INDEX IF NOT EXISTS idx_category_group_member_cat ON category_group_member (category_id);

--changeset nexadrop:v65-002 splitStatements:true endDelimiter:;
--comment: Grupo "Calzado" por defecto (idempotente).
INSERT INTO category_group (id, name, description)
SELECT gen_random_uuid(), 'Calzado', 'Todas las categorías de zapatos/calzado — regla de margen única'
WHERE NOT EXISTS (SELECT 1 FROM category_group WHERE name = 'Calzado');

--changeset nexadrop:v65-003 runAlways:true splitStatements:true endDelimiter:;
--comment: Miembros del grupo Calzado = categorías de calzado genuino (excluye accesorios/muebles/snacks). runAlways: en cada arranque se reañaden las categorías de calzado nuevas (ON CONFLICT ignora las que ya están), de modo que futuras categorías de calzado quedan cubiertas solas.
INSERT INTO category_group_member (group_id, category_id)
SELECT (SELECT id FROM category_group WHERE name = 'Calzado'), c.id
FROM category c
JOIN category_translation ct ON ct.category_id = c.id AND ct.language = 'es'
WHERE (
        ct.name ILIKE '%zapato%'     OR ct.name ILIKE '%zapatilla%' OR ct.name ILIKE '%sandalia%'
     OR ct.name ILIKE '%bota%'       OR ct.name ILIKE '%chancla%'   OR ct.name ILIKE '%zueco%'
     OR ct.name ILIKE '%pantufla%'   OR ct.name ILIKE '%calzado%'   OR ct.name ILIKE '%mocasin%'
     OR ct.name ILIKE '%alpargata%'  OR ct.name ILIKE '%tenis%'
      )
  AND ct.name NOT ILIKE '%botana%'    AND ct.name NOT ILIKE '%plantilla%' AND ct.name NOT ILIKE '%hebilla%'
  AND ct.name NOT ILIKE '%estante%'   AND ct.name NOT ILIKE '%banco%'     AND ct.name NOT ILIKE '%accesorio%'
  AND ct.name NOT ILIKE '%cordón%'    AND ct.name NOT ILIKE '%cordones%'  AND ct.name NOT ILIKE '%betún%'
ON CONFLICT DO NOTHING;

--changeset nexadrop:v65-004 splitStatements:true endDelimiter:;
--comment: Regla de margen 300% para todo el calzado (scope CATEGORY_GROUP → grupo Calzado). Canal STOREFRONT. Idempotente.
INSERT INTO price_rule (id, scope, scope_id, margin_type, margin_value, active, position, channel, description)
SELECT gen_random_uuid(), 'CATEGORY_GROUP', (SELECT id FROM category_group WHERE name = 'Calzado'),
       'PERCENTAGE', 300.00, true, 0, 'STOREFRONT', 'Margen del calzado (grupo de categorías)'
WHERE NOT EXISTS (
    SELECT 1 FROM price_rule
    WHERE scope = 'CATEGORY_GROUP' AND scope_id = (SELECT id FROM category_group WHERE name = 'Calzado')
);
