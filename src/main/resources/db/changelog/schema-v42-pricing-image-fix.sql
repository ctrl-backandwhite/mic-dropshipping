--liquibase formatted sql

--changeset nexadrop:v42-supplier-rule-scope
-- DROP-630: las reglas seed de scope SUPPLIER tenían scope_id NULL → la columna "Alcance"
-- mostraba "Proveedor" sin nombre concreto (no trazable). Les asignamos un proveedor real
-- (distribuido) para que scopeName resuelva el nombre del proveedor.
WITH ranked AS (
    SELECT id, row_number() OVER (ORDER BY id) AS rn
    FROM price_rule WHERE scope = 'SUPPLIER' AND scope_id IS NULL
),
sups AS (
    SELECT id, row_number() OVER (ORDER BY id) AS rn, count(*) OVER () AS cnt FROM supplier
)
UPDATE price_rule p SET scope_id = s.id
FROM ranked r JOIN sups s ON s.rn = ((r.rn - 1) % NULLIF(s.cnt, 0)) + 1
WHERE p.id = r.id;

--changeset nexadrop:v42-fix-cable-image-real
-- DROP-638: la imagen del Cable USB-C (OFFER-01008) seguía sin corresponder (la v35 usó una foto
-- que no es un cable). La sustituimos por una foto Unsplash VERIFICADA de cables USB
-- (photo-1573868388390-2739872961e6, "USB data cables").
UPDATE product_image pi
SET source_url = 'https://images.unsplash.com/photo-1573868388390-2739872961e6?w=800',
    cdn_url = 'https://images.unsplash.com/photo-1573868388390-2739872961e6?w=800',
    updated_at = now()
FROM product p
WHERE pi.product_id = p.id
  AND p.external_id = 'OFFER-01008'
  AND pi.role = 'MAIN';

-- y cualquier producto de cable que aún use la foto incorrecta de la v35 (no es un cable)
UPDATE product_image pi
SET source_url = REPLACE(pi.source_url, 'photo-1588872657578-7efd1f1555ed', 'photo-1573868388390-2739872961e6'),
    cdn_url = CASE WHEN pi.cdn_url IS NULL THEN NULL
                   ELSE REPLACE(pi.cdn_url, 'photo-1588872657578-7efd1f1555ed', 'photo-1573868388390-2739872961e6') END,
    updated_at = now()
FROM product p
WHERE pi.product_id = p.id
  AND pi.source_url ILIKE '%photo-1588872657578-7efd1f1555ed%'
  AND p.title_zh ~* '(数据线|cable|usb-c)';
