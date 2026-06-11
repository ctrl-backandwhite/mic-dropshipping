--liquibase formatted sql
--changeset nexadrop:v21-pod-blanks
-- DROP-505: el catálogo POD aparece vacío porque ningún producto tiene
-- pod_enabled=true en BD. Activamos POD para todos los productos de
-- fashion-apparel (camisetas, sudaderas) + tazas/toys que tienen sentido
-- como blank para print-on-demand.

UPDATE product
SET pod_enabled = TRUE
WHERE category_id IN (
    SELECT id FROM category
    WHERE slug IN ('fashion-apparel', 'toys-gifts', 'home-kitchen', 'beauty-personal-care')
);
