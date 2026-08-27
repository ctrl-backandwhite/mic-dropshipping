--liquibase formatted sql

-- Quita la imagen a los valores de variante donde NO distingue nada.
--
-- Continuación de v127, que limpió el eje Talla por nombre. Aquí el criterio ya no es cómo se llama el eje,
-- sino qué hace la imagen: **si todos los valores de un eje comparten exactamente la misma foto, esa foto
-- no informa de nada**. El comprador elige entre «Oro» y «Plata» viendo dos veces la misma imagen.
--
-- Medido en el catálogo antes de aplicarlo:
--   Acabado (Oro/Plata)      2 valores → 1 sola imagen  → ruido
--   Longitud (105-130 cm)    8 valores → la principal   → ruido
--   Tamaño (8/12 cm)         2 valores → 1 sola imagen  → ruido
--   Tipo, Versión            2 valores → 1 sola imagen  → ruido
--   Especificación          11 valores → 3 imágenes     → SÍ distingue, se conserva
--   Lente                    6 valores → 3 imágenes     → SÍ distingue, se conserva
--
-- El eje COLOR queda FUERA a propósito. La regla del catálogo es «color = imagen»: si un color no tiene su
-- foto real, lo que corresponde es asignársela, no quitarle la que tenga. Un color con imagen repetida es
-- un defecto de carga que se arregla poniendo la buena, y eso exige mirar la ficha de origen.

--changeset nexa:v128-imagen-variante-que-no-distingue
UPDATE variant_value vv
   SET image_cdn_url = NULL,
       image_source_url = NULL,
       updated_at = now()
 WHERE (vv.image_cdn_url IS NOT NULL OR vv.image_source_url IS NOT NULL)
   AND vv.option_id IN (
        SELECT vo.id
          FROM variant_option vo
          JOIN variant_value v2 ON v2.option_id = vo.id
         WHERE COALESCE(NULLIF(vo.name, ''), vo.name_zh) NOT IN ('Color', 'Colores', '颜色', '颜色分类')
         GROUP BY vo.id
        HAVING count(*) > 1
           AND count(v2.image_cdn_url) > 0
           -- La clave: una sola imagen distinta para TODO el eje ⇒ no separa un valor de otro.
           AND count(DISTINCT v2.image_cdn_url) <= 1
   );
