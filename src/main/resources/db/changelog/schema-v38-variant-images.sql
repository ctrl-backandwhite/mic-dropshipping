--liquibase formatted sql
--changeset nexadrop:v38-variant-images
-- Se están creando variantes para cada producto pero sin imagen propia: las 763
-- variantes (y los 108 valores de variante/color) quedaban con image_source_url NULL,
-- así que la miniatura de variante no existía en admin ni en el storefront.
--
-- Como las variantes son colores del mismo producto y no hay fotos por color en el
-- catálogo, asignamos a cada variante una imagen REAL de su producto. Para dar variedad
-- cuando el producto tiene varias imágenes, distribuimos por orden: la variante n-ésima
-- recibe la imagen (n mod nº_de_imágenes) ordenadas por posición. Productos con una sola
-- imagen → todas sus variantes usan esa. Sólo se rellena lo que está vacío (idempotente).

WITH ranked_variants AS (
    SELECT v.id AS variant_id,
           v.product_id,
           (row_number() OVER (PARTITION BY v.product_id ORDER BY v.sku, v.id) - 1) AS vord
    FROM product_variant v
    WHERE COALESCE(v.image_source_url, v.image_cdn_url) IS NULL
),
prod_imgs AS (
    SELECT pi.product_id,
           pi.source_url AS url,
           (row_number() OVER (PARTITION BY pi.product_id ORDER BY pi.position, pi.id) - 1) AS iord,
           count(*)       OVER (PARTITION BY pi.product_id) AS icnt
    FROM product_image pi
    WHERE pi.source_url IS NOT NULL
)
UPDATE product_variant v
SET image_source_url = pim.url,
    updated_at = now()
FROM ranked_variants rv
JOIN prod_imgs pim ON pim.product_id = rv.product_id
                  AND pim.iord = (rv.vord % pim.icnt)
WHERE v.id = rv.variant_id;

--changeset nexadrop:v38-variant-value-images
-- Los valores de opción (swatches de color) también estaban sin imagen: asignamos la
-- imagen principal (menor posición) del producto de su opción. Idempotente.
UPDATE variant_value vv
SET image_source_url = (
        SELECT pi.source_url
        FROM product_image pi
        JOIN variant_option vo ON vo.product_id = pi.product_id
        WHERE vo.id = vv.option_id
          AND pi.source_url IS NOT NULL
        ORDER BY pi.position, pi.id
        LIMIT 1
    ),
    updated_at = now()
WHERE COALESCE(vv.image_source_url, vv.image_cdn_url) IS NULL;
