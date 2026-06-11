--liquibase formatted sql

--changeset nexadrop:v8c-created-at-001 splitStatements:true endDelimiter:;
ALTER TABLE product_warehouse_stock ADD COLUMN IF NOT EXISTS created_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE shop_product_listing    ADD COLUMN IF NOT EXISTS created_at timestamptz NOT NULL DEFAULT now();
