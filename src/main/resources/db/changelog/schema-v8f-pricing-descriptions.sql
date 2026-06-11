--liquibase formatted sql

--changeset nexadrop:v8f-pricing-desc-001 splitStatements:true endDelimiter:;
--comment: DROP-164/165/206/207 — replace literal currency/English fragments in seeded price-rule descriptions.

UPDATE price_rule
SET description = 'Proveedor preferido — ' || SUBSTRING(description FROM LENGTH('Supplier preferido — ') + 1)
WHERE description LIKE 'Supplier preferido — %';

UPDATE price_rule
SET description = 'Proveedor preferido — ' || SUBSTRING(description FROM LENGTH('Supplier preferido: ') + 1)
WHERE description LIKE 'Supplier preferido: %';

UPDATE price_rule
SET description = 'Recargo fijo para productos de bajo coste'
WHERE description IN (
  'Cargo fijo $1.50 (ítems < $5)',
  'Cargo fijo $1.50 (items < $5)',
  'Cargo fijo de $1.50 (ítems < $5)'
);
