--liquibase formatted sql

--changeset nexadrop:v6-catalog-deep-001 splitStatements:true endDelimiter:;
--comment: Deep catalog data — specifications, attributes, tags, shipping zones/rates, fulfillment metadata.

-- ============ product extra columns ============
ALTER TABLE product
    ADD COLUMN IF NOT EXISTS package_weight_grams int,
    ADD COLUMN IF NOT EXISTS lead_time_days       int,
    ADD COLUMN IF NOT EXISTS warranty_months      int,
    ADD COLUMN IF NOT EXISTS country_of_origin    varchar(2),
    ADD COLUMN IF NOT EXISTS certifications       jsonb DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS return_policy_days   int;

-- ============ free-form key/value technical specifications (multi-lang) ============
CREATE TABLE IF NOT EXISTS product_specification (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id uuid NOT NULL REFERENCES product(id) ON DELETE CASCADE,
    locale     varchar(8)  NOT NULL,
    spec_key   varchar(80) NOT NULL,
    spec_value varchar(800) NOT NULL,
    position   int NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_product_spec_product   ON product_specification(product_id);
CREATE INDEX IF NOT EXISTS idx_product_spec_key       ON product_specification(spec_key);
CREATE INDEX IF NOT EXISTS idx_product_spec_p_locale  ON product_specification(product_id, locale);

-- ============ taxonomic attributes (key catalog + product binding for faceting) ============
CREATE TABLE IF NOT EXISTS product_attribute (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id uuid NOT NULL REFERENCES product(id) ON DELETE CASCADE,
    attr_key   varchar(60)  NOT NULL,  -- brand | season | age_group | room | use_case | gender
    attr_value varchar(120) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_product_attr_product ON product_attribute(product_id);
CREATE INDEX IF NOT EXISTS idx_product_attr_kv      ON product_attribute(attr_key, attr_value);

-- ============ free-text tags (search hints) ============
CREATE TABLE IF NOT EXISTS product_keyword (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id uuid NOT NULL REFERENCES product(id) ON DELETE CASCADE,
    tag        varchar(80)  NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_product_keyword_product ON product_keyword(product_id);
CREATE INDEX IF NOT EXISTS idx_product_keyword_tag     ON product_keyword(LOWER(tag));

-- ============ shipping zones (which destination countries each supplier serves) ============
CREATE TABLE IF NOT EXISTS shipping_zone (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    supplier_id  uuid NOT NULL REFERENCES supplier(id) ON DELETE CASCADE,
    country_code varchar(2) NOT NULL,
    region       varchar(60),
    active       boolean NOT NULL DEFAULT true,
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (supplier_id, country_code)
);

CREATE INDEX IF NOT EXISTS idx_shipping_zone_supplier ON shipping_zone(supplier_id);
CREATE INDEX IF NOT EXISTS idx_shipping_zone_country  ON shipping_zone(country_code);

-- ============ shipping rates ============
CREATE TABLE IF NOT EXISTS shipping_rate (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    supplier_id        uuid NOT NULL REFERENCES supplier(id) ON DELETE CASCADE,
    country_code       varchar(2) NOT NULL,
    method             varchar(20) NOT NULL,             -- STANDARD | EXPRESS | SEA | AIR | PICKUP
    carrier            varchar(60),
    transit_days_min   int NOT NULL,
    transit_days_max   int NOT NULL,
    base_cents         int NOT NULL DEFAULT 0,           -- flat fee component
    per_kg_cents       int NOT NULL DEFAULT 0,           -- per-kg surcharge
    min_weight_grams   int,
    max_weight_grams   int,
    insurance_pct      numeric(5,2),
    active             boolean NOT NULL DEFAULT true,
    created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_shipping_rate_supplier         ON shipping_rate(supplier_id);
CREATE INDEX IF NOT EXISTS idx_shipping_rate_supplier_country ON shipping_rate(supplier_id, country_code, active);
CREATE INDEX IF NOT EXISTS idx_shipping_rate_method           ON shipping_rate(method);

-- ============ search performance — pg_trgm fuzzy + jsonb GIN ============
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_product_translation_title_trgm
    ON product_translation USING gin (LOWER(title) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_product_title_zh_trgm
    ON product USING gin (LOWER(title_zh) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_product_certifications_gin
    ON product USING gin (certifications);

-- composite indexes for common filter combos
CREATE INDEX IF NOT EXISTS idx_product_status_category_trend
    ON product(status, category_id, trend_score DESC NULLS LAST);

CREATE INDEX IF NOT EXISTS idx_product_status_supplier_sales
    ON product(status, supplier_id, monthly_sales DESC NULLS LAST);

CREATE INDEX IF NOT EXISTS idx_product_status_price
    ON product(status, base_price);
