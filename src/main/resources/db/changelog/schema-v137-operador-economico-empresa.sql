--liquibase formatted sql

--changeset nexadrop:v137-001-operador-economico-empresa splitStatements:false
-- EL OPERADOR ECONÓMICO ES LA EMPRESA, NO UNA PERSONA. El dato se había rellenado con el nombre y el
-- domicilio particular del titular, y salía impreso en TODAS las facturas que recibe cada cliente.
-- Ninguna plataforma comparable publica ahí a una persona física: publican la razón social.
--
-- Solo se toca si el valor sigue siendo el nombre personal, para no pisar una configuración que alguien
-- haya corregido ya a mano en su entorno.
UPDATE eu_responsible_person
SET name = 'NX036 LLC',
    updated_at = now(),
    updated_by = 'v137'
WHERE id = 1
  AND name = 'Jesús Enrique Finol Finol';
