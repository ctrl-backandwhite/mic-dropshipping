--liquibase formatted sql

--changeset nexadrop:v100-customs-block-over-threshold splitStatements:false
-- Bloquear el pedido cuando supera el umbral de importación del destino, en vez de aceptarlo.
--
-- Motivo: la única línea contratada de YunExpress es de e-commerce simplificado (IOSS en la UE, VOEC
-- en Noruega, régimen de bajo valor en el resto). Esa línea NO despacha formalmente: por encima del
-- umbral del país (150 € en la UE, 135 £ en UK, etc.) el paquete simplemente NO se acepta. Con la
-- política SURCHARGE el checkout aceptaba y cobraba un pedido que luego la línea rechazaría → habría
-- que reembolsar. Con BLOCK, el checkout lo impide ANTES de cobrar y avisa al cliente.
--
-- El umbral se mide sobre el VALOR INTRÍNSECO (subtotal de producto tras descuento, sin envío ni IVA),
-- así que también se dispara con un carrito de muchos artículos baratos que sumen más del límite, no
-- solo con un artículo caro. Solo afecta a países con umbral configurado (de_minimis_amount > 0); los
-- que están a 0 (umbral no evaluado) no cambian de comportamiento.
UPDATE country_customs_rule
   SET over_threshold_policy = 'BLOCK'
 WHERE over_threshold_policy = 'SURCHARGE'
   AND de_minimis_amount > 0;
