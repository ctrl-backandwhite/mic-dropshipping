--liquibase formatted sql

--changeset nexadrop:v107-001-eu-margin-seed splitStatements:false
-- Margen por país para la UE-27: 104% en el canal STOREFRONT. Sube el margen base (100%) lo justo para
-- cubrir la comisión del 2% que YunExpress cobra por adelantar el IVA (sin IOSS). Ojo: el margen es % sobre
-- el COSTE y la comisión es 2% sobre la VENTA (~2× el coste), así que 100→104 (no 102) es lo que cubre el 2%
-- real. Una regla con país gana sobre la GLOBAL sin país cuando el país efectivo del comprador coincide.
-- Idempotente: no duplica si ya existe una regla GLOBAL/STOREFRONT para ese país.
INSERT INTO price_rule (id, scope, scope_id, margin_type, margin_value, min_cost_usd, max_cost_usd,
                        active, position, description, channel, country_code, created_at, updated_at)
SELECT gen_random_uuid(), 'GLOBAL', NULL, 'PERCENTAGE', 104.0000, NULL, NULL,
       true, 0, 'Margen UE 104% (base + comisión 2% prepago IVA YunExpress)', 'STOREFRONT', v.cc, now(), now()
FROM (VALUES ('AT'),('BE'),('BG'),('HR'),('CY'),('CZ'),('DK'),('EE'),('FI'),('FR'),('DE'),('GR'),('HU'),
             ('IE'),('IT'),('LV'),('LT'),('LU'),('MT'),('NL'),('PL'),('PT'),('RO'),('SK'),('SI'),('ES'),
             ('SE')) AS v(cc)
WHERE NOT EXISTS (
    SELECT 1 FROM price_rule r
    WHERE r.scope = 'GLOBAL' AND r.channel = 'STOREFRONT' AND r.country_code = v.cc
);

--changeset nexadrop:v107-002-rest-of-world-margin splitStatements:false
-- Resto del mundo (todo país SIN regla propia): margen base 120% en el escaparate. Es la regla GLOBAL sin
-- país; los 27 de la UE la sobrescriben con su 104%. Se actualiza la regla base existente (no se crea otra).
UPDATE price_rule
   SET margin_value = 120.0000, updated_at = now()
 WHERE scope = 'GLOBAL' AND channel = 'STOREFRONT' AND country_code IS NULL;

