--liquibase formatted sql

--changeset nexadrop:v8e-pricing-001 splitStatements:true endDelimiter:;
--comment: DROP-126 — collapse duplicate GLOBAL/PERCENTAGE/35% price rules (legacy SQL + Java seed inserted both).

DELETE FROM price_rule
USING (
  SELECT id FROM price_rule
  WHERE scope = 'GLOBAL' AND scope_id IS NULL
    AND margin_type = 'PERCENTAGE' AND margin_value = 35.00
  ORDER BY created_at DESC
  OFFSET 1
) dups
WHERE price_rule.id = dups.id;

--changeset nexadrop:v8e-pricing-002 splitStatements:true endDelimiter:;
--comment: DROP-125 — replace category slugs in rule descriptions with the localized category name when available.

UPDATE price_rule pr
SET description = 'Margen categoría — ' || COALESCE(
  (SELECT name FROM category_translation ct WHERE ct.category_id = pr.scope_id AND ct.language = 'es' LIMIT 1),
  (SELECT name FROM category_translation ct WHERE ct.category_id = pr.scope_id AND ct.language = 'en' LIMIT 1),
  (SELECT name_zh FROM category c WHERE c.id = pr.scope_id),
  (SELECT slug   FROM category c WHERE c.id = pr.scope_id)
)
WHERE pr.scope = 'CATEGORY'
  AND pr.scope_id IS NOT NULL
  AND (pr.description IS NULL OR pr.description LIKE 'Margen categoría %');
