--liquibase formatted sql

-- Retira del catálogo lo que no se puede vender o enviar a la Unión Europea por la línea contratada, y
-- corrige tres pesos imposibles.
--
-- Decisión del titular del negocio (14-ago-2026) tras revisar el catálogo contra el §0 del playbook de
-- carga y las líneas de servicio realmente contratadas a YunExpress. Son 194 referencias en tres grupos:
--
--   · 89  gafas de lectura y de montura → producto sanitario en la UE (Reglamento (UE) 2017/745): exigen
--          marcado CE y su propio régimen, que el proveedor de 1688 no acredita.
--   · 83  aparatos eléctricos con batería (secadores, rizadores, cortapelos, altavoces, ollas…) → no
--          caben en la línea de ropa 云途全球服装专线挂号, que solo admite bolsa y complementos textiles;
--          necesitarían la línea 特惠带电, que es un contrato aparte.
--   · 22  cosmética (uñas y pestañas postizas, cepillos) → canal propio 化妆品类; los formatos líquidos
--          están directamente prohibidos.
--
-- Va como migración y no como limpieza manual en cada entorno porque así se aplica igual en local,
-- desarrollo y pre, queda registrada y no depende de que alguien recuerde repetirla. Es IDEMPOTENTE: en
-- un entorno donde ya se hizo a mano, no encuentra nada y no falla.

--changeset nexa:v124-retirar-no-enviables-ue splitStatements:false
DO $retirar$
DECLARE
    v_con_pedidos int;
    v_borrados    int;
BEGIN
    CREATE TEMP TABLE IF NOT EXISTS objetivo_v124 AS
    SELECT DISTINCT p.id
    FROM product p
    JOIN category c ON c.id = p.category_id
    LEFT JOIN category_translation ct ON ct.category_id = c.id AND ct.language = 'es'
    WHERE ct.name IN ('Gafas de lectura', 'Gafas de montura')
       OR c.slug LIKE 'bell-dis%'   -- belleza · dispositivos eléctricos
       OR c.slug LIKE 'hog-ele%'    -- hogar · electrodomésticos
       OR c.slug LIKE 'bell-cos%';  -- belleza · cosmética

    -- Salvaguarda: un producto vendido NO se borra. `order_item` es la única clave ajena sin CASCADE que
    -- guarda historial real, y perder la línea de un pedido por una limpieza de catálogo sería mucho peor
    -- que dejar el producto. Si aparece alguno, la migración aborta y hay que decidir a mano.
    SELECT count(*) INTO v_con_pedidos
      FROM order_item oi WHERE oi.product_id IN (SELECT id FROM objetivo_v124);
    IF v_con_pedidos > 0 THEN
        RAISE EXCEPTION 'v124 abortada: % líneas de pedido referencian productos a retirar', v_con_pedidos;
    END IF;

    -- category_ranking es la otra ajena sin CASCADE, pero es caché de posiciones: se puede borrar.
    DELETE FROM category_ranking WHERE product_id IN (SELECT id FROM objetivo_v124);
    DELETE FROM product WHERE id IN (SELECT id FROM objetivo_v124);
    GET DIAGNOSTICS v_borrados = ROW_COUNT;
    RAISE NOTICE 'v124: % productos retirados del catálogo', v_borrados;

    DROP TABLE IF EXISTS objetivo_v124;
END
$retirar$;

--changeset nexa:v124-corregir-pesos-imposibles
-- Tres pesos que 1688 publica mal en su propio campo `weight` (con `unitWeight` a 0), no un fallo de la
-- carga: 500.000 g para una chaqueta, 3.900 g para una camiseta y 2.877 g para un polo. Ninguno es
-- interpretable en gramos, kilos ni miligramos de forma coherente, así que no hay peso real que recuperar
-- del proveedor. Se sustituyen por la MEDIANA de su categoría medida sobre el propio catálogo —mediana y
-- no media, porque un solo atípico como estos desplaza la media y dejaría el arreglo tan mal como el
-- problema—: 500 g la chaqueta, 252 g la camiseta y 300 g el polo.
--
-- Importa porque el peso es lo que cotiza el flete: 500 kg declarados en una chaqueta harían imposible
-- comprarla, y un peso demasiado bajo se traduce en un recargo del transportista después del envío.
UPDATE product_variant v SET package_weight_grams = 500, weight_grams = 500
  FROM product p WHERE p.id = v.product_id AND p.external_id = '962473598367';

UPDATE product_variant v SET package_weight_grams = 252, weight_grams = 252
  FROM product p WHERE p.id = v.product_id AND p.external_id = '670882879717';

UPDATE product_variant v SET package_weight_grams = 300, weight_grams = 300
  FROM product p WHERE p.id = v.product_id AND p.external_id = '1035727749093';
