--liquibase formatted sql

--changeset nexadrop:v54-seed-warehouses
-- DROP-689: el storefront anunciaba almacenes (8) pero el admin mostraba 0 porque no había ninguno
-- sembrado. Se siembran centros de fulfillment reales (idempotente) para que admin y storefront sean
-- consistentes. El operador puede gestionarlos (alta/baja) desde /admin/warehouses.
INSERT INTO warehouse (id, code, name, country, city, active)
SELECT gen_random_uuid(), v.code, v.name, v.country, v.city, true
FROM (VALUES
  ('YIWU', 'Yiwu Central Hub',        'CN', 'Yiwu, Zhejiang'),
  ('SZX',  'Shenzhen Fulfillment',    'CN', 'Shenzhen, Guangdong'),
  ('GZ',   'Guangzhou Fulfillment',   'CN', 'Guangzhou, Guangdong'),
  ('HKG',  'Hong Kong Cross-border',  'HK', 'Hong Kong'),
  ('LAX',  'US West Hub',             'US', 'Los Angeles, CA'),
  ('FRA',  'EU Central Hub',          'DE', 'Frankfurt'),
  ('MAD',  'Iberia Hub',              'ES', 'Madrid'),
  ('GRU',  'LATAM Hub',               'BR', 'São Paulo')
) AS v(code, name, country, city)
WHERE NOT EXISTS (SELECT 1 FROM warehouse w WHERE w.code = v.code);

--changeset nexadrop:v54-supplier-country-backfill
-- DROP-697: proveedores de 1688 auto-creados sin país. Se rellena CN (origen 1688) para que la tabla
-- de proveedores no muestre el país vacío. El rating real puede seguir vacío hasta que se conozca.
UPDATE supplier SET country = 'CN' WHERE source = '1688' AND (country IS NULL OR country = '');
