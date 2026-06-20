--liquibase formatted sql

--changeset nexadrop:v62-001 splitStatements:true endDelimiter:;
--comment: Solo se persiste el precio en CNY. Las columnas USD eran vestigiales (nunca escritas ni leídas; el precio USD/EUR se calcula en vivo desde el CNY con la tasa del día). Se eliminan.
ALTER TABLE product            DROP COLUMN IF EXISTS cost_usd;
ALTER TABLE product            DROP COLUMN IF EXISTS retail_usd;
ALTER TABLE product_variant    DROP COLUMN IF EXISTS cost_usd;
ALTER TABLE product_variant    DROP COLUMN IF EXISTS retail_usd;
ALTER TABLE product_price_tier DROP COLUMN IF EXISTS unit_price_usd;

--changeset nexadrop:v62-002 splitStatements:true endDelimiter:;
--comment: Canal de la regla de margen. STOREFRONT = tienda propia; INTEGRATION = apps conectadas por API (Shopify/WooCommerce). Permite que convivan un margen de storefront y otro de integración sin colisionar.
ALTER TABLE price_rule ADD COLUMN IF NOT EXISTS channel VARCHAR(20) NOT NULL DEFAULT 'STOREFRONT';

--changeset nexadrop:v62-003 splitStatements:true endDelimiter:;
--comment: Seed del margen GLOBAL por defecto del storefront (150%). Solo si no existe ya una regla GLOBAL de storefront (idempotente; no duplica la que el admin pudiera tener).
INSERT INTO price_rule (id, scope, scope_id, margin_type, margin_value, active, position, channel, description)
SELECT gen_random_uuid(), 'GLOBAL', NULL, 'PERCENTAGE', 150.00, true, 0, 'STOREFRONT',
       'Margen global por defecto (tienda propia)'
WHERE NOT EXISTS (SELECT 1 FROM price_rule WHERE scope = 'GLOBAL' AND channel = 'STOREFRONT');

--changeset nexadrop:v62-004 splitStatements:true endDelimiter:;
--comment: Seed del margen para apps conectadas por API/integración (75%). Canal INTEGRATION: solo aplica cuando la petición proviene de una integración (Shopify/WooCommerce), no en el storefront.
INSERT INTO price_rule (id, scope, scope_id, margin_type, margin_value, active, position, channel, description)
SELECT gen_random_uuid(), 'GLOBAL', NULL, 'PERCENTAGE', 75.00, true, 0, 'INTEGRATION',
       'Margen para apps conectadas por API/integración (Shopify/WooCommerce)'
WHERE NOT EXISTS (SELECT 1 FROM price_rule WHERE scope = 'GLOBAL' AND channel = 'INTEGRATION');
