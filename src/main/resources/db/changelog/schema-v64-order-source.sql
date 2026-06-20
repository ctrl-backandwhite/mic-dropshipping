--liquibase formatted sql

--changeset nexadrop:v64-001 splitStatements:true endDelimiter:;
--comment: Origen de la orden: PLATFORM (tienda propia NX036) o INTEGRATION (tienda conectada Shopify/WooCommerce/API). Determina la comisión del operador (10% propias, 5% integradas).
ALTER TABLE customer_order ADD COLUMN IF NOT EXISTS source VARCHAR(20) NOT NULL DEFAULT 'PLATFORM';

--changeset nexadrop:v64-002 splitStatements:true endDelimiter:;
--comment: Backfill: las órdenes con partnerAppId (creadas por la API de partners) se marcan como INTEGRATION.
UPDATE customer_order SET source = 'INTEGRATION' WHERE partner_app_id IS NOT NULL AND source = 'PLATFORM';

--changeset nexadrop:v64-003 splitStatements:true endDelimiter:;
--comment: Guardar en el histórico del operador el origen y el % aplicado, para trazabilidad del reporte admin.
ALTER TABLE operator_order_action ADD COLUMN IF NOT EXISTS order_source VARCHAR(20);
ALTER TABLE operator_order_action ADD COLUMN IF NOT EXISTS commission_pct NUMERIC(5,2);
