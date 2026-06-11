--liquibase formatted sql
--changeset nexadrop:v20-product-image-backfill
-- DROP-496: el dashboard reportaba que sólo los productos "Bestseller" mostraban
-- miniatura — los demás se quedaron sin imagen después de DROP-412 (v19 borró
-- URLs placeholder y muchos productos quedaron con galería vacía). Insertamos
-- una imagen Unsplash por categoría como fallback para que las tarjetas y el
-- PDP nunca rendericen un cuadro vacío.

INSERT INTO product_image (id, product_id, source_url, cdn_url, position, role, created_at, updated_at)
SELECT gen_random_uuid(),
       p.id,
       CASE
         WHEN c.slug = 'consumer-electronics' THEN 'https://images.unsplash.com/photo-1606220945770-b5b6c2c55bf1?w=800'
         WHEN c.slug = 'fashion-apparel'      THEN 'https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=800'
         WHEN c.slug = 'home-kitchen'         THEN 'https://images.unsplash.com/photo-1583863788434-e58a36330cf0?w=800'
         WHEN c.slug = 'beauty-personal-care' THEN 'https://images.unsplash.com/photo-1609592424823-15ad7e2deb12?w=800'
         WHEN c.slug = 'sports-outdoors'      THEN 'https://images.unsplash.com/photo-1558002038-1055907df827?w=800'
         WHEN c.slug = 'toys-gifts'           THEN 'https://images.unsplash.com/photo-1626379617675-1d5a8c842f30?w=800'
         WHEN c.slug = 'auto-parts'           THEN 'https://images.unsplash.com/photo-1581235720704-06d3acfcb36f?w=800'
         WHEN c.slug = 'office-supplies'      THEN 'https://images.unsplash.com/photo-1567427361984-0cbe7396fc6c?w=800'
         WHEN c.slug = 'pet-supplies'         THEN 'https://images.unsplash.com/photo-1583337130417-3346a1be7dee?w=800'
         WHEN c.slug = 'tools-hardware'       THEN 'https://images.unsplash.com/photo-1530124566582-a618bc2615dc?w=800'
         WHEN c.slug = 'jewelry-watches'      THEN 'https://images.unsplash.com/photo-1611591437281-460bfbe1220a?w=800'
         WHEN c.slug = 'garden-outdoor'       THEN 'https://images.unsplash.com/photo-1416879595882-3373a0480b5b?w=800'
         WHEN c.slug = 'baby-maternity'       THEN 'https://images.unsplash.com/photo-1522771930-78848d9293e8?w=800'
         WHEN c.slug = 'lighting'             THEN 'https://images.unsplash.com/photo-1513506003901-1e6a229e2d15?w=800'
         ELSE 'https://images.unsplash.com/photo-1607082348824-0a96f2a4b9da?w=800'
       END,
       NULL,
       0,
       'MAIN',
       now(),
       now()
FROM product p
LEFT JOIN category c ON c.id = p.category_id
WHERE NOT EXISTS (SELECT 1 FROM product_image pi WHERE pi.product_id = p.id);
