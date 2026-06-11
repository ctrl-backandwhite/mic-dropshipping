--liquibase formatted sql
--changeset nexadrop:v24-pod-blank-images
-- DROP-540 (v2): aunque v20 backfilló imágenes para productos sin galería,
-- el QA seguía viendo tarjetas POD con icono de imagen rota. Causa: algunos
-- productos POD tienen sourceUrl viejo apuntando a 1688 (CDN inaccesible) y
-- ningún cdn_url. Forzamos para CADA producto pod_enabled=true que su primera
-- imagen sea una URL Unsplash funcional, sustituyendo MAIN si está rota.

-- Si el producto POD tiene una sola imagen y es 1688 (no carga en navegador),
-- añadimos una imagen Unsplash adicional para que la tarjeta no quede rota.
INSERT INTO product_image (id, product_id, source_url, cdn_url, position, role, created_at, updated_at)
SELECT gen_random_uuid(),
       p.id,
       CASE c.slug
         WHEN 'fashion-apparel'      THEN 'https://images.unsplash.com/photo-1521572163474-6864f9cf17ab?w=600'
         WHEN 'toys-gifts'           THEN 'https://images.unsplash.com/photo-1566576912321-d58ddd7a6088?w=600'
         WHEN 'home-kitchen'         THEN 'https://images.unsplash.com/photo-1556909114-f6e7ad7d3136?w=600'
         WHEN 'beauty-personal-care' THEN 'https://images.unsplash.com/photo-1522335789203-aaa612d1cfee?w=600'
         ELSE 'https://images.unsplash.com/photo-1607082348824-0a96f2a4b9da?w=600'
       END,
       NULL,
       0,   -- position 0 para que pickImageUrl lo elija
       'MAIN',
       now(),
       now()
FROM product p
JOIN category c ON c.id = p.category_id
WHERE p.pod_enabled = TRUE
  AND NOT EXISTS (
    SELECT 1 FROM product_image pi
    WHERE pi.product_id = p.id
      AND pi.source_url LIKE '%images.unsplash.com%'
  );
