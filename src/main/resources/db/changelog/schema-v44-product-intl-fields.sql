--liquibase formatted sql

--changeset nexadrop:v44-product-intl-columns
-- Campos internacionales/1688 que faltaban en product (los demás ya existían).
ALTER TABLE product ADD COLUMN IF NOT EXISTS sales_regions jsonb;
ALTER TABLE product ADD COLUMN IF NOT EXISTS video_urls jsonb;
ALTER TABLE product ADD COLUMN IF NOT EXISTS rating_breakdown jsonb;
ALTER TABLE product ADD COLUMN IF NOT EXISTS cross_border_support jsonb;
ALTER TABLE product ADD COLUMN IF NOT EXISTS dropship_shipped_30d integer;
ALTER TABLE product ADD COLUMN IF NOT EXISTS dropship_pickup_rate_48h numeric(5,2);

--changeset nexadrop:v44-variant-supplier-sku
-- SKU del proveedor (1688) por variante, para reaprovisionar.
ALTER TABLE product_variant ADD COLUMN IF NOT EXISTS supplier_sku_id varchar(120);
