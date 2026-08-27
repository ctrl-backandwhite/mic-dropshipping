--liquibase formatted sql

-- Retira los productos que superan los 2 kg del canal BPA contratado: 10 sillas de oficina y gaming, de 9
-- a 20 kg.
--
-- Su peso es CORRECTO —una silla pesa eso— y el producto es perfectamente legal. Lo que falla es que no
-- caben en la línea contratada, así que un cliente podría comprarlas y el pedido se quedaría sin poder
-- despacharse: el cliente paga y su compra se atasca.
--
-- En local una de ellas tenía un pedido de PRUEBA (NX-1785149919-8710, cuenta del propio titular, sin
-- movimiento de wallet). Se borró junto con su pago porque el negocio aún no ha salido a producción de
-- forma oficial y esa venta no era real. En desarrollo y pre no hay pedidos asociados, así que aquí el
-- borrado es directo.
--
-- La comprobación de `order_item` se mantiene igual que en v124/v125: si algún día esta migración corre en
-- un entorno CON ventas reales, aborta con un mensaje legible en vez de estrellarse contra la clave ajena.

--changeset nexa:v126-retirar-sobre-2kg splitStatements:false
DO $pesadas$
DECLARE
    v_con_pedidos int;
    v_borradas    int;
BEGIN
    -- El peso efectivo es el mayor entre el del producto y el de sus variantes: la báscula de 1688 es por
    -- SKU, así que muchos productos solo lo tienen a nivel de variante.
    CREATE TEMP TABLE IF NOT EXISTS pesadas_v126 AS
    SELECT p.id
    FROM product p
    WHERE p.status = 'ACTIVE'
      AND GREATEST(
            COALESCE(p.package_weight_grams, 0),
            COALESCE(p.weight_grams, 0),
            COALESCE((SELECT MAX(GREATEST(COALESCE(pv.package_weight_grams, 0),
                                          COALESCE(pv.weight_grams, 0)))
                        FROM product_variant pv WHERE pv.product_id = p.id), 0)
          ) > 2000;

    SELECT count(*) INTO v_con_pedidos
      FROM order_item oi WHERE oi.product_id IN (SELECT id FROM pesadas_v126);
    IF v_con_pedidos > 0 THEN
        RAISE EXCEPTION 'v126 abortada: % líneas de pedido referencian productos de más de 2 kg. '
                        'Revisar a mano: si son ventas reales, archivar en vez de borrar', v_con_pedidos;
    END IF;

    DELETE FROM category_ranking WHERE product_id IN (SELECT id FROM pesadas_v126);
    DELETE FROM product WHERE id IN (SELECT id FROM pesadas_v126);
    GET DIAGNOSTICS v_borradas = ROW_COUNT;
    RAISE NOTICE 'v126: % productos de más de 2 kg retirados del catálogo', v_borradas;

    DROP TABLE IF EXISTS pesadas_v126;
END
$pesadas$;
