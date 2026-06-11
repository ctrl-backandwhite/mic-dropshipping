--liquibase formatted sql

--changeset nexadrop:v11-product-images splitStatements:false runOnChange:false endDelimiter:;
--comment: DROP-266 — replace picsum URLs (random photos) with placeholders that show the product slug, so what the user sees matches the product name. Color per category.

UPDATE product_image pi
SET source_url =
    'https://placehold.co/800x800/'
    || CASE c.slug
        WHEN 'consumer-electronics' THEN 'bfdbfe/1e3a8a'   -- blue
        WHEN 'fashion-apparel'      THEN 'fbcfe8/9d174d'   -- pink
        WHEN 'home-kitchen'         THEN 'fde68a/92400e'   -- amber
        WHEN 'beauty-personal-care' THEN 'fbcfe8/be185d'   -- rose
        WHEN 'sports-outdoors'      THEN 'a7f3d0/064e3b'   -- emerald
        WHEN 'toys-gifts'           THEN 'ddd6fe/5b21b6'   -- violet
        ELSE                              'e5e7eb/374151'   -- slate
       END
    || '/png?text='
    || regexp_replace(coalesce(p.title_zh, p.slug), '[^A-Za-z0-9 ]', '', 'g'),
    cdn_url = NULL,
    mirror_status = 'PENDING'
FROM product p
LEFT JOIN category c ON c.id = p.category_id
WHERE pi.product_id = p.id
  AND (pi.source_url IS NULL OR pi.source_url LIKE '%picsum.photos%');
