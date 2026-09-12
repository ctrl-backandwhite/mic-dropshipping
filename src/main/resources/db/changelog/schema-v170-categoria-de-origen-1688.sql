--liquibase formatted sql

--changeset nexadrop:v170-categoria-de-origen-1688
--comment La categoria que 1688 declara para cada producto, guardada tal cual. No decide donde se
--archiva -de eso se encarga categorySlug- pero sin ella no hay forma de AUDITAR la clasificacion
--despues de cargar: el 11-sep-2026 habia 656 productos mal archivados y hubo que deducirlo del
--titulo, que es el mismo dato con el que se habia equivocado el clasificador. Con esta columna se
--puede comparar contra lo que dijo el proveedor, y poblar el mapeo category_1688_mapping sin
--adivinar.

-- ADD COLUMN es instantaneo, pero para entrar necesita el cerrojo exclusivo de la tabla y se pone
-- A LA COLA. Si alguien tiene una transaccion abierta sobre product -el 12-sep-2026 una conexion del
-- backend viejo llevaba 20 minutos «idle in transaction»-, la ALTER espera, y detras de ella se
-- encola TODA consulta posterior a product: el catalogo de PRE se quedo parado 17 minutos y el pod
-- murio al agotar la sonda de arranque, dejando ademas el cerrojo de Liquibase cogido.
--
-- Con lock_timeout la migracion falla en 5 segundos en vez de congelar la tienda. Fallar es
-- preferible: el despliegue se reintenta y no hay nadie esperando. Vale para toda la sesion de
-- Liquibase, asi que protege tambien al CREATE INDEX de abajo.
SET lock_timeout = '5s';

ALTER TABLE product ADD COLUMN category_1688_id varchar(60);
ALTER TABLE product ADD COLUMN category_1688_name varchar(200);

--changeset nexadrop:v170-indice-categoria-1688
--comment Para agrupar por categoria de origen al auditar, que es justo para lo que existe.
CREATE INDEX idx_product_category_1688 ON product (category_1688_id);
