--liquibase formatted sql

--changeset nexadrop:v45-clear-price-rules
-- Las reglas de margen NO se siembran automáticamente: el operador las crea (decisión 2026-06-13).
-- Borra las sembradas por la migración v2 (se ejecuta después de ella) y las demo existentes.
-- En una BD nueva: v2 inserta las suyas y esta migración las elimina → tabla limpia al arrancar.
DELETE FROM price_rule;
