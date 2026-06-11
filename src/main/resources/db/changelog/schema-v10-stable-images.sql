--liquibase formatted sql

--changeset nexadrop:v10-stable-product-images splitStatements:false runOnChange:false endDelimiter:;
--comment: Replace flaky Unsplash photo URLs with deterministic picsum.photos seeded by product slug, so every product card always renders.

UPDATE product_image pi
SET source_url = 'https://picsum.photos/seed/' || p.slug || '-' || pi.position || '/800/800',
    cdn_url = NULL,
    mirror_status = 'PENDING'
FROM product p
WHERE pi.product_id = p.id;

--changeset nexadrop:v10-ensure-main-image splitStatements:false endDelimiter:;
-- Promote the first image of each product to MAIN if no MAIN image exists.
WITH first_image AS (
    SELECT DISTINCT ON (product_id) id, product_id
    FROM product_image
    ORDER BY product_id, position ASC, created_at ASC
)
UPDATE product_image pi
SET role = 'MAIN'
FROM first_image fi
WHERE pi.id = fi.id
  AND NOT EXISTS (
    SELECT 1 FROM product_image x
    WHERE x.product_id = pi.product_id AND x.role = 'MAIN' AND x.id <> pi.id
  );
