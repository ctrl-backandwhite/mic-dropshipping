--liquibase formatted sql

--changeset nexadrop:v166-precio-visto-en-el-historial
--comment El historial guardaba solo QUE se habia visitado un producto, no a que precio. Para pintarlo
--habia que volver a calcular el precio de cada una de las cincuenta fichas: convertir la divisa, aplicar
--el margen del pais de registro, el IVA, el envio, las dos bolsas de subvencion y el recargo fijo. Eso es
--lo que hacia lenta la pagina, no la consulta de las visitas.
--
--Ahora se guarda el precio TAL COMO LO VIO esa persona, ya calculado, en el momento de anotar la visita.
--Listar el historial pasa a ser leer filas.
--
--Lo calcula el SERVIDOR al registrar la visita; no llega del navegador. Si el importe viajara desde el
--cliente, cualquiera podria anotarse el precio que quisiera.
--
--El importe formateado se guarda hecho ("28,26 €") porque el formato depende del idioma y de la moneda de
--quien mira, y esa combinacion es justamente la que ya estaba resuelta cuando se vio la ficha.
--
--Nulos permitidos: las visitas anteriores a este cambio no tienen precio guardado, y el listado cae para
--ellas al calculo de siempre.

ALTER TABLE product_view ADD COLUMN IF NOT EXISTS precio_visto NUMERIC(18, 4);
ALTER TABLE product_view ADD COLUMN IF NOT EXISTS moneda_vista VARCHAR(3);
ALTER TABLE product_view ADD COLUMN IF NOT EXISTS precio_visto_formateado VARCHAR(40);
