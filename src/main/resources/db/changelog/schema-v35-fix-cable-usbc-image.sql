--liquibase formatted sql
--changeset nexadrop:v35-fix-cable-usbc-image
-- DROP-638 [Catálogo]: el producto "Cable USB-C 100W trenzado 2m PD"
-- (external_id OFFER-01008, slug 100w-2-pd-offer-01008) mostraba como imagen
-- principal una foto de AURICULARES en vez de un cable USB-C.
--
-- CAUSA RAÍZ: el mapping por keyword de la migración v26
-- (schema-v26-product-images-by-title.sql, línea del patrón '数据线|cable|usb-c')
-- asigna a los cables la URL Unsplash 'photo-1583394838336-acd977736f90', que en
-- realidad es una foto de auriculares/earbuds blancos — NO un cable. Como esa URL
-- devuelve 200 (carga bien), la migración v28 (fix de imágenes rotas) la incluyó
-- en su whitelist de "URLs verificadas" y nunca se detectó: el problema no es un
-- 404, es que el sujeto de la foto no corresponde al producto.
--
-- ALCANCE: en la BD real sólo OFFER-01008 quedó con esa foto de auriculares como
-- imagen principal (los demás productos "USB-C" son del filler GEN-* y usan el
-- pool genérico de electrónica, no esta URL). Corregimos por tanto cualquier
-- product_image que use la foto de auriculares en un producto cuyo título sea de
-- cable/USB-C, cubriendo OFFER-01008 y blindando ante futuras re-ejecuciones del
-- seed. Sustituimos por una foto Unsplash real de cable USB-C verificada (200):
--   photo-1588872657578-7efd1f1555ed  (cable de carga USB-C).

UPDATE product_image pi
SET source_url = REPLACE(pi.source_url,
        'photo-1583394838336-acd977736f90',
        'photo-1588872657578-7efd1f1555ed'),
    updated_at = now()
FROM product p
WHERE pi.product_id = p.id
  AND pi.source_url ILIKE '%photo-1583394838336-acd977736f90%'
  AND p.title_zh ~* '(数据线|cable|usb-c)';

-- Refuerzo explícito y determinista para el producto del ticket (idempotente):
-- garantiza que la imagen MAIN de OFFER-01008 sea la del cable USB-C aunque la
-- foto de auriculares se hubiera persistido con otra variante de URL.
UPDATE product_image pi
SET source_url = 'https://images.unsplash.com/photo-1588872657578-7efd1f1555ed?w=800',
    updated_at = now()
FROM product p
WHERE pi.product_id = p.id
  AND p.external_id = 'OFFER-01008'
  AND pi.role = 'MAIN'
  AND pi.source_url ILIKE '%photo-1583394838336-acd977736f90%';
