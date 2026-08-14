--liquibase formatted sql

-- Quita la imagen a los valores del eje TALLA.
--
-- La regla del catálogo es «color = imagen»: la foto por variante existe para que el comprador vea CÓMO
-- ES ese color. Una talla no cambia el aspecto de la prenda —una S y una L del mismo vestido son la misma
-- foto—, así que asignarle imagen no aporta nada y ensucia el dato.
--
-- No es un caso aislado: 25.063 valores de talla tenían imagen, y en 22.900 de ellos (el 91 %) era
-- literalmente la foto PRINCIPAL del producto. Es ruido de la carga, no una decisión.
--
-- Solo se toca el eje Talla. Los demás ejes visuales (Estampado, Modelo, Estilo…) conservan su imagen: ahí
-- sí distingue, porque un estampado distinto se ve. Y Color, obviamente, no se toca.

--changeset nexa:v127-tallas-sin-imagen
UPDATE variant_value vv
   SET image_cdn_url = NULL,
       image_source_url = NULL,
       updated_at = now()
  FROM variant_option vo
 WHERE vo.id = vv.option_id
   AND COALESCE(NULLIF(vo.name, ''), vo.name_zh) IN ('Talla', 'Tallas', '尺码', '尺寸')
   AND (vv.image_cdn_url IS NOT NULL OR vv.image_source_url IS NOT NULL);
