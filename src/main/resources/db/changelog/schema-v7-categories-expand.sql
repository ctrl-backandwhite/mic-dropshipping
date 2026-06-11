--liquibase formatted sql

--changeset nexadrop:v7-categories-expand-001 splitStatements:true endDelimiter:;
--comment: Expand from 6 to 14 root categories aligned with the marketplace mega-menu.

-- Add metadata columns to product for future-proof filtering (DROP-17, DROP-22)
ALTER TABLE product
    ADD COLUMN IF NOT EXISTS ship_from        varchar(2),
    ADD COLUMN IF NOT EXISTS free_shipping    boolean   DEFAULT false,
    ADD COLUMN IF NOT EXISTS self_pickup      boolean   DEFAULT false,
    ADD COLUMN IF NOT EXISTS has_video        boolean   DEFAULT false,
    ADD COLUMN IF NOT EXISTS video_url        varchar(800),
    ADD COLUMN IF NOT EXISTS inventory_count  int       DEFAULT 0;

-- Indexes for the new filters used in /products listing
CREATE INDEX IF NOT EXISTS idx_product_ship_from     ON product(ship_from)       WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_product_free_shipping ON product(free_shipping)   WHERE status = 'ACTIVE' AND free_shipping = true;
CREATE INDEX IF NOT EXISTS idx_product_has_video     ON product(has_video)       WHERE status = 'ACTIVE' AND has_video = true;
CREATE INDEX IF NOT EXISTS idx_product_inventory     ON product(inventory_count DESC NULLS LAST) WHERE status = 'ACTIVE';

-- Daily price/stock snapshots for product history charts (DROP-25)
CREATE TABLE IF NOT EXISTS product_history (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id      uuid NOT NULL REFERENCES product(id) ON DELETE CASCADE,
    snapshot_date   date NOT NULL,
    price_usd_cents int  NOT NULL,
    stock           int  NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (product_id, snapshot_date)
);

CREATE INDEX IF NOT EXISTS idx_product_history_product_date
    ON product_history(product_id, snapshot_date DESC);
