--liquibase formatted sql

-- Retira 7 adornos navideños que llevan pilas o LED.
--
-- Continuación de v124: el catálogo navideño se revisó aparte porque llegó después. De 39 artículos, 32
-- son adornos textiles, de resina o de hierro y entran sin problema en la línea de ropa contratada; estos
-- 7 llevan electrónica y necesitarían la línea 特惠带电, que es un contrato distinto.
--
-- Uno de ellos, la "Casita de nieve de resina blanca luminosa", no se detectaba por palabras clave: su
-- ficha en español dice "luminosa" y no menciona pilas ni LED. Se confirmó abriendo la oferta real en
-- 1688, cuyo título lleva 发光 ("que emite luz"). De ahí la lección para futuras cargas: "luminoso",
-- "brillante" o "que ilumina" pueden ser un acabado o pueden ser una bombilla, y solo la ficha de origen
-- lo aclara.
--
-- Idempotente y con la misma salvaguarda que v124: si algún producto tiene líneas de pedido, aborta.

--changeset nexa:v125-retirar-navidenos-electricos splitStatements:false
DO $navidad$
DECLARE
    v_con_pedidos int;
    v_borrados    int;
BEGIN
    CREATE TEMP TABLE IF NOT EXISTS objetivo_v125 AS
    SELECT id FROM product WHERE external_id IN (
        '821223580662',  -- Casita de nieve de resina blanca luminosa (发光 en la ficha de origen)
        '981892508241',  -- Decoración LED de Navidad de madera para mesa
        '955831438984',  -- Farol navideño LED con vela parpadeante
        '651218926561',  -- Farolillo navideño LED con vela parpadeante
        '827700010643',  -- Farolillo navideño triangular con luz LED cálida
        '850861954784',  -- Mini árbol de Navidad decorativo de sobremesa
        '828858003800'   -- Tren de Navidad colgante eléctrico con vía
    );

    SELECT count(*) INTO v_con_pedidos
      FROM order_item oi WHERE oi.product_id IN (SELECT id FROM objetivo_v125);
    IF v_con_pedidos > 0 THEN
        RAISE EXCEPTION 'v125 abortada: % líneas de pedido referencian adornos a retirar', v_con_pedidos;
    END IF;

    DELETE FROM category_ranking WHERE product_id IN (SELECT id FROM objetivo_v125);
    DELETE FROM product WHERE id IN (SELECT id FROM objetivo_v125);
    GET DIAGNOSTICS v_borrados = ROW_COUNT;
    RAISE NOTICE 'v125: % adornos navideños con electrónica retirados', v_borrados;

    DROP TABLE IF EXISTS objetivo_v125;
END
$navidad$;
