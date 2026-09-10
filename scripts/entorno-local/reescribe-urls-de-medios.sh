#!/usr/bin/env bash
# Apunta las URLs de los medios guardadas en la base de datos a una dirección alcanzable.
#
# POR QUÉ HACE FALTA: las URLs se guardan ABSOLUTAS, con el host incluido. En el entorno local eso
# es `http://localhost:9100`, que funciona en el navegador del ordenador y NO en el emulador de
# Android: allí «localhost» es el propio teléfono. La imagen no carga y no hay ningún error: se ve
# un hueco gris y se lee como «todavía está cargando».
#
# Ya pasó una vez con las fotos del producto y se arreglaron solo esas: quedaron fuera la imagen de
# la variante, la muestra de color del eje, el vídeo y las fotos congeladas en la cesta y en el
# pedido. Con la muestra de color rota, el selector de color de la ficha salía en blanco y no había
# forma de elegir color. Por eso este guion las recorre TODAS.
#
# Uso:  ./reescribe-urls-de-medios.sh 192.168.1.7        # la IP de la máquina en la red local
#       ./reescribe-urls-de-medios.sh localhost          # para volver a dejarlo como estaba
set -euo pipefail

DESTINO="${1:-}"
CONTENEDOR="${PG_CONTAINER:-nexadrop-postgres}"
USUARIO="${PG_USER:-nexadrop}"
BASE="${PG_DB:-nexadrop}"
PUERTO="${MEDIA_PORT:-9100}"

if [ -z "$DESTINO" ]; then
    echo "Falta el host de destino. Ejemplo: $0 192.168.1.7" >&2
    exit 1
fi

# Se reescribe cualquier host, no solo «localhost»: al cambiar de red la IP de ayer tampoco vale.
#
# PERO SOLO lo que vive en NUESTRO almacén, y eso se reconoce por el camino `/product-images/`. La
# primera versión de este guion cambiaba el host de cualquier URL y convirtió 29 fotos congeladas de
# pedidos antiguos —que apuntaban al proveedor, no a nosotros— en enlaces locales que daban 403. Una
# reescritura a ciegas rompe justo los datos que no había que tocar.
SQL=$(cat <<EOF
DO \$\$
DECLARE
    destino text := 'http://${DESTINO}:${PUERTO}/';
    patron  text := '^https?://[^/]+/(?=product-images/)';
BEGIN
    UPDATE product_image  SET cdn_url = regexp_replace(cdn_url, patron, destino)
        WHERE cdn_url ~ patron AND cdn_url !~ ('^http://${DESTINO}:${PUERTO}/');
    UPDATE product_variant SET image_cdn_url = regexp_replace(image_cdn_url, patron, destino)
        WHERE image_cdn_url ~ patron AND image_cdn_url !~ ('^http://${DESTINO}:${PUERTO}/');
    UPDATE variant_value  SET image_cdn_url = regexp_replace(image_cdn_url, patron, destino)
        WHERE image_cdn_url ~ patron AND image_cdn_url !~ ('^http://${DESTINO}:${PUERTO}/');
    UPDATE product        SET video_cdn_url = regexp_replace(video_cdn_url, patron, destino)
        WHERE video_cdn_url ~ patron AND video_cdn_url !~ ('^http://${DESTINO}:${PUERTO}/');
    -- Las fotos congeladas de la cesta y del pedido: son copias del momento de comprar y por eso
    -- no se recalculan solas al arreglar el catálogo.
    UPDATE cart_item      SET image_url = regexp_replace(image_url, patron, destino)
        WHERE image_url ~ patron AND image_url !~ ('^http://${DESTINO}:${PUERTO}/');
    UPDATE saved_cart_item SET image_url = regexp_replace(image_url, patron, destino)
        WHERE image_url ~ patron AND image_url !~ ('^http://${DESTINO}:${PUERTO}/');
    UPDATE order_item     SET image_url_snapshot = regexp_replace(image_url_snapshot, patron, destino)
        WHERE image_url_snapshot ~ patron AND image_url_snapshot !~ ('^http://${DESTINO}:${PUERTO}/');
END
\$\$;

-- Pendientes: solo lo NUESTRO. Una URL del proveedor no está pendiente de nada, está bien como está.
SELECT 'product_image'   AS tabla, count(*) AS pendientes FROM product_image  WHERE cdn_url LIKE '%/product-images/%' AND cdn_url NOT LIKE 'http://${DESTINO}:${PUERTO}/%'
UNION ALL SELECT 'product_variant', count(*) FROM product_variant WHERE image_cdn_url LIKE '%/product-images/%' AND image_cdn_url NOT LIKE 'http://${DESTINO}:${PUERTO}/%'
UNION ALL SELECT 'variant_value',   count(*) FROM variant_value   WHERE image_cdn_url LIKE '%/product-images/%' AND image_cdn_url NOT LIKE 'http://${DESTINO}:${PUERTO}/%'
UNION ALL SELECT 'product.video',   count(*) FROM product         WHERE video_cdn_url LIKE '%/product-images/%' AND video_cdn_url NOT LIKE 'http://${DESTINO}:${PUERTO}/%'
UNION ALL SELECT 'cart_item',       count(*) FROM cart_item       WHERE image_url LIKE '%/product-images/%' AND image_url NOT LIKE 'http://${DESTINO}:${PUERTO}/%'
UNION ALL SELECT 'saved_cart_item', count(*) FROM saved_cart_item WHERE image_url LIKE '%/product-images/%' AND image_url NOT LIKE 'http://${DESTINO}:${PUERTO}/%'
UNION ALL SELECT 'order_item',      count(*) FROM order_item      WHERE image_url_snapshot LIKE '%/product-images/%' AND image_url_snapshot NOT LIKE 'http://${DESTINO}:${PUERTO}/%';
EOF
)

echo "$SQL" | docker exec -i "$CONTENEDOR" psql -U "$USUARIO" -d "$BASE" -v ON_ERROR_STOP=1
