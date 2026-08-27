--liquibase formatted sql

--changeset nexadrop:v109-001-activate-base-margin splitStatements:false
-- La regla de margen base del escaparate (GLOBAL, sin país) estaba DESACTIVADA, así que los países fuera de
-- la UE (sin regla propia) se quedaban sin margen (precio = coste). Se activa y se fija al 120% acordado
-- para el resto del mundo. Los 27 de la UE la sobrescriben con su 104%.
UPDATE price_rule
   SET active = true, margin_value = 120.0000, updated_at = now()
 WHERE scope = 'GLOBAL' AND channel = 'STOREFRONT' AND country_code IS NULL;
