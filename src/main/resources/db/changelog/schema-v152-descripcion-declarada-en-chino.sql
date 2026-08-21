--liquibase formatted sql

--changeset nexadrop:v152-descripcion-declarada-en-chino splitStatements:false
--
-- Snapshot del CName con el que se declaró la línea, hermano de `declared_description`.
--
-- Una línea de la declaración lleva UN EName y UN CName, y los dos tienen que describir la misma
-- mercancía. Al fusionar por grupo, el inglés pasa a ser el genérico aprobado mientras que el chino
-- se resolvía del primer artículo que cayó en la línea: la aduana leería dos mercancías distintas en
-- la misma línea, y el CName es campo que YunExpress valida antes de emitir la guía.
--
-- Va congelado y no resuelto al despachar por el mismo motivo que el inglés: si el grupo se edita o se
-- desaprueba después de cobrar, este pedido tiene que seguir declarando lo que declaró. Nulo en los
-- pedidos anteriores, que se resuelven por el título del producto, que es como se declararon.
--
ALTER TABLE order_item ADD COLUMN declared_description_zh varchar(512);
