-- Valores de variante que el comprador ve en chino.
--
-- QUÉ MIRA: cada par (producto, valor) que aparece en `product_variant.options_json` y que NO existe
-- como valor del eje en `variant_value`. Cuando falta, `VariantOptionTranslator` no encuentra nada
-- que poner y deja pasar el texto original del proveedor: en la aplicación salía una talla como
-- «L【105-130斤】» dentro de un pedido ya pagado.
--
-- POR QUÉ AQUÍ: el fallo no da ningún error. La ficha se pinta, el pedido se paga y el chino solo se
-- ve mirando la pantalla. Esta consulta es la única forma de encontrarlo sin ir producto a producto.
--
-- USO:  docker exec nexadrop-postgres psql -U nexadrop -d nexadrop -f - < variantes-sin-traduccion.sql
WITH pares AS (
    SELECT DISTINCT pv.product_id, kv.key AS eje, kv.value AS valor_zh
    FROM product_variant pv, LATERAL jsonb_each_text(pv.options_json::jsonb) kv
    WHERE pv.options_json IS NOT NULL
)
SELECT p.external_id, p.id AS product_id, pares.eje, pares.valor_zh
FROM pares
JOIN product p ON p.id = pares.product_id
WHERE NOT EXISTS (
        SELECT 1
        FROM variant_option vo
        JOIN variant_value vv ON vv.option_id = vo.id
        WHERE vo.product_id = pares.product_id
          AND vv.value_zh = pares.valor_zh)
ORDER BY p.external_id, pares.eje, pares.valor_zh;
