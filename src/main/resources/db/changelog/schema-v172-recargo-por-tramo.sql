--liquibase formatted sql

--changeset nexadrop:v172-recargo-por-tramo
--comment El recargo fijo deja de ser uno por producto y pasa a poder fijarse POR TRAMO de cantidad.
--El motivo es que el recargo cubre un coste que NO escala con la cantidad -gestion de la compra,
--manipulado, la parte fija del despacho-, y cobrarlo igual a quien se lleva una unidad que a quien
--se lleva diez mil encarece el pedido grande justo donde el precio por cantidad promete lo
--contrario. El envio y el arancel siguen siendo UNO por producto: esos si escalan con el bulto.
--
--Nulo NO es cero: significa «este tramo no tiene recargo propio, usa el del producto». Es lo que
--deja la columna vacia en los 9.718 productos ya cargados sin cambiarles el precio ni un centimo.
--Y por eso la columna se deja NULLABLE y sin DEFAULT: un DEFAULT 0 aqui pondria a cero el recargo
--de todos los tramos existentes el dia del despliegue.

-- Mismo cerrojo acotado que en v170: un ADD COLUMN es instantaneo, pero espera al cerrojo exclusivo
-- de la tabla y detras de el se encola todo el catalogo. Fallar en 5 segundos y reintentar el
-- despliegue es preferible a dejar la tienda parada.
SET lock_timeout = '5s';

ALTER TABLE product_price_tier ADD COLUMN surcharge_cny numeric(12,4);
