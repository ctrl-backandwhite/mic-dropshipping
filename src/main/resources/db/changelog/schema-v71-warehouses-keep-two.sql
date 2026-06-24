--liquibase formatted sql

--changeset nexadrop:v71-warehouses-keep-two
-- Solo se quieren DOS almacenes globales (el resto se sembraba en v9/v54 y se acumulaban extras
-- por entorno). Se conservan SZX (Shenzhen, origen real de 1688) y MAD (Iberia/Madrid, mercado
-- destino del operador); el resto se elimina junto con su stock dependiente para no violar la FK
-- product_warehouse_stock.warehouse_id. Idempotente: el NOT IN deja el set en exactamente esos 2.
-- Para cambiar QUÉ dos almacenes se conservan, edita la lista de códigos en ambas sentencias.
DELETE FROM product_warehouse_stock
WHERE warehouse_id IN (SELECT id FROM warehouse WHERE code NOT IN ('SZX', 'MAD'));

DELETE FROM warehouse WHERE code NOT IN ('SZX', 'MAD');
