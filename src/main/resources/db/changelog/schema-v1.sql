--liquibase formatted sql

--changeset nexadrop:001-extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";

--changeset nexadrop:001-users
CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(255),
    role VARCHAR(30) NOT NULL DEFAULT 'USER',
    active BOOLEAN NOT NULL DEFAULT FALSE,
    activation_code VARCHAR(64),
    activation_code_expires_at TIMESTAMP WITH TIME ZONE,
    failed_login_count INT DEFAULT 0,
    locked_until TIMESTAMP WITH TIME ZONE,
    last_login TIMESTAMP WITH TIME ZONE,
    display_name VARCHAR(120),
    company_name VARCHAR(180),
    country VARCHAR(60),
    phone VARCHAR(40),
    avatar_url VARCHAR(500),
    language VARCHAR(8) DEFAULT 'es',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_users_email ON users(email);

--changeset nexadrop:001-oauth-registered-client
CREATE TABLE oauth2_registered_client (
    id VARCHAR(100) PRIMARY KEY,
    client_id VARCHAR(100) NOT NULL,
    client_id_issued_at TIMESTAMP WITH TIME ZONE DEFAULT now(),
    client_secret VARCHAR(200),
    client_secret_expires_at TIMESTAMP WITH TIME ZONE,
    client_name VARCHAR(200) NOT NULL,
    client_authentication_methods VARCHAR(1000) NOT NULL,
    authorization_grant_types VARCHAR(1000) NOT NULL,
    redirect_uris VARCHAR(1000),
    post_logout_redirect_uris VARCHAR(1000),
    scopes VARCHAR(1000) NOT NULL,
    client_settings VARCHAR(2000) NOT NULL,
    token_settings VARCHAR(2000) NOT NULL
);

--changeset nexadrop:001-oauth-authorization
CREATE TABLE oauth2_authorization (
    id VARCHAR(100) PRIMARY KEY,
    registered_client_id VARCHAR(100) NOT NULL,
    principal_name VARCHAR(200) NOT NULL,
    authorization_grant_type VARCHAR(100) NOT NULL,
    authorized_scopes VARCHAR(1000),
    attributes TEXT,
    state VARCHAR(500),
    authorization_code_value TEXT,
    authorization_code_issued_at TIMESTAMP WITH TIME ZONE,
    authorization_code_expires_at TIMESTAMP WITH TIME ZONE,
    authorization_code_metadata TEXT,
    access_token_value TEXT,
    access_token_issued_at TIMESTAMP WITH TIME ZONE,
    access_token_expires_at TIMESTAMP WITH TIME ZONE,
    access_token_metadata TEXT,
    access_token_type VARCHAR(100),
    access_token_scopes VARCHAR(1000),
    oidc_id_token_value TEXT,
    oidc_id_token_issued_at TIMESTAMP WITH TIME ZONE,
    oidc_id_token_expires_at TIMESTAMP WITH TIME ZONE,
    oidc_id_token_metadata TEXT,
    refresh_token_value TEXT,
    refresh_token_issued_at TIMESTAMP WITH TIME ZONE,
    refresh_token_expires_at TIMESTAMP WITH TIME ZONE,
    refresh_token_metadata TEXT,
    user_code_value TEXT,
    user_code_issued_at TIMESTAMP WITH TIME ZONE,
    user_code_expires_at TIMESTAMP WITH TIME ZONE,
    user_code_metadata TEXT,
    device_code_value TEXT,
    device_code_issued_at TIMESTAMP WITH TIME ZONE,
    device_code_expires_at TIMESTAMP WITH TIME ZONE,
    device_code_metadata TEXT
);

--changeset nexadrop:001-oauth-authorization-consent
CREATE TABLE oauth2_authorization_consent (
    registered_client_id VARCHAR(100) NOT NULL,
    principal_name VARCHAR(200) NOT NULL,
    authorities VARCHAR(1000) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
);

--changeset nexadrop:001-jwk-keys
CREATE TABLE jwk_keys (
    id UUID PRIMARY KEY,
    kid VARCHAR(100) NOT NULL UNIQUE,
    public_key TEXT NOT NULL,
    private_key TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    rotated_at TIMESTAMP WITH TIME ZONE
);

--changeset nexadrop:002-supplier
CREATE TABLE supplier (
    id UUID PRIMARY KEY,
    external_id VARCHAR(100) NOT NULL,
    source VARCHAR(40) NOT NULL,
    name VARCHAR(300),
    name_zh VARCHAR(300),
    country VARCHAR(60) DEFAULT 'CN',
    city VARCHAR(120),
    rating NUMERIC(3,2),
    years_active INT,
    verified BOOLEAN DEFAULT FALSE,
    trust_pass BOOLEAN DEFAULT FALSE,
    profile_url VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT ux_supplier_source_external UNIQUE (source, external_id)
);

--changeset nexadrop:002-category
CREATE TABLE category (
    id UUID PRIMARY KEY,
    slug VARCHAR(200) NOT NULL UNIQUE,
    parent_id UUID REFERENCES category(id),
    source VARCHAR(40) DEFAULT '1688',
    external_id VARCHAR(100),
    name_zh VARCHAR(200),
    position INT DEFAULT 0,
    active BOOLEAN DEFAULT TRUE,
    icon VARCHAR(120),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_category_parent ON category(parent_id);

--changeset nexadrop:002-category-translation
CREATE TABLE category_translation (
    id UUID PRIMARY KEY,
    category_id UUID NOT NULL REFERENCES category(id) ON DELETE CASCADE,
    language VARCHAR(8) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT ux_cattr_cat_lang UNIQUE (category_id, language)
);

--changeset nexadrop:002-product
CREATE TABLE product (
    id UUID PRIMARY KEY,
    slug VARCHAR(220) NOT NULL UNIQUE,
    external_id VARCHAR(120) NOT NULL,
    source VARCHAR(40) NOT NULL DEFAULT '1688',
    supplier_id UUID REFERENCES supplier(id),
    category_id UUID REFERENCES category(id),
    title_zh VARCHAR(500) NOT NULL,
    short_description_zh VARCHAR(2000),
    description_zh TEXT,
    brand VARCHAR(200),
    moq INT DEFAULT 1,
    base_price NUMERIC(12,4),
    currency VARCHAR(8) DEFAULT 'CNY',
    weight_grams INT,
    length_mm INT,
    width_mm INT,
    height_mm INT,
    hs_code VARCHAR(40),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    rating NUMERIC(3,2),
    review_count INT DEFAULT 0,
    monthly_sales INT DEFAULT 0,
    repurchase_rate NUMERIC(5,2),
    trend_score NUMERIC(8,4) DEFAULT 0,
    source_url VARCHAR(800),
    ingested_at TIMESTAMP WITH TIME ZONE,
    last_synced_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT ux_product_source_external UNIQUE (source, external_id)
);
CREATE INDEX idx_product_supplier ON product(supplier_id);
CREATE INDEX idx_product_category ON product(category_id);
CREATE INDEX idx_product_status ON product(status);
CREATE INDEX idx_product_trend_score ON product(trend_score DESC);

--changeset nexadrop:002-product-translation
CREATE TABLE product_translation (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES product(id) ON DELETE CASCADE,
    language VARCHAR(8) NOT NULL,
    title VARCHAR(500),
    short_description VARCHAR(2000),
    description TEXT,
    meta_title VARCHAR(200),
    meta_description VARCHAR(400),
    provider VARCHAR(40),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT ux_prodtr_prod_lang UNIQUE (product_id, language)
);

--changeset nexadrop:002-product-image
CREATE TABLE product_image (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES product(id) ON DELETE CASCADE,
    position INT DEFAULT 0,
    role VARCHAR(30) DEFAULT 'GALLERY',
    source_url VARCHAR(800) NOT NULL,
    cdn_url VARCHAR(800),
    width INT,
    height INT,
    bytes BIGINT,
    hash VARCHAR(80),
    mirror_status VARCHAR(20) DEFAULT 'PENDING',
    mirrored_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_image_product ON product_image(product_id);

--changeset nexadrop:002-product-variant
CREATE TABLE product_variant (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES product(id) ON DELETE CASCADE,
    external_id VARCHAR(120),
    sku VARCHAR(120),
    title VARCHAR(400),
    price NUMERIC(12,4),
    stock INT DEFAULT 0,
    weight_grams INT,
    barcode VARCHAR(80),
    image_source_url VARCHAR(800),
    image_cdn_url VARCHAR(800),
    options_json JSONB,
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_variant_product ON product_variant(product_id);
CREATE INDEX idx_variant_sku ON product_variant(sku);

--changeset nexadrop:002-variant-option
CREATE TABLE variant_option (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES product(id) ON DELETE CASCADE,
    name_zh VARCHAR(120) NOT NULL,
    name VARCHAR(120),
    position INT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

--changeset nexadrop:002-variant-value
CREATE TABLE variant_value (
    id UUID PRIMARY KEY,
    option_id UUID NOT NULL REFERENCES variant_option(id) ON DELETE CASCADE,
    value_zh VARCHAR(200) NOT NULL,
    value VARCHAR(200),
    image_source_url VARCHAR(800),
    image_cdn_url VARCHAR(800),
    position INT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

--changeset nexadrop:002-product-tag
CREATE TABLE product_tag (
    id UUID PRIMARY KEY,
    slug VARCHAR(80) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    kind VARCHAR(20) DEFAULT 'TAG'
);
CREATE TABLE product_tag_link (
    product_id UUID NOT NULL REFERENCES product(id) ON DELETE CASCADE,
    tag_id UUID NOT NULL REFERENCES product_tag(id) ON DELETE CASCADE,
    PRIMARY KEY (product_id, tag_id)
);

--changeset nexadrop:003-price-tier
CREATE TABLE product_price_tier (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES product(id) ON DELETE CASCADE,
    variant_id UUID REFERENCES product_variant(id) ON DELETE CASCADE,
    min_qty INT NOT NULL,
    max_qty INT,
    unit_price NUMERIC(12,4) NOT NULL,
    currency VARCHAR(8) DEFAULT 'CNY',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_pricetier_product ON product_price_tier(product_id);

--changeset nexadrop:003-inventory-snapshot
CREATE TABLE inventory_snapshot (
    id UUID PRIMARY KEY,
    variant_id UUID NOT NULL REFERENCES product_variant(id) ON DELETE CASCADE,
    stock INT NOT NULL,
    captured_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_invsnap_variant_time ON inventory_snapshot(variant_id, captured_at DESC);

--changeset nexadrop:004-trend-signal
CREATE TABLE trend_signal (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES product(id) ON DELETE CASCADE,
    source VARCHAR(40) NOT NULL,
    signal_type VARCHAR(40) NOT NULL,
    value NUMERIC(14,4),
    rank_position INT,
    captured_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    meta_json JSONB
);
CREATE INDEX idx_trend_prod_source ON trend_signal(product_id, source, captured_at DESC);

--changeset nexadrop:004-bestseller-ranking
CREATE TABLE bestseller_ranking (
    id UUID PRIMARY KEY,
    list_code VARCHAR(60) NOT NULL,
    product_id UUID NOT NULL REFERENCES product(id) ON DELETE CASCADE,
    rank_position INT NOT NULL,
    captured_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    source_url VARCHAR(800)
);
CREATE INDEX idx_bsr_list_time ON bestseller_ranking(list_code, captured_at DESC, rank_position);

--changeset nexadrop:004-category-ranking
CREATE TABLE category_ranking (
    id UUID PRIMARY KEY,
    category_id UUID NOT NULL REFERENCES category(id),
    product_id UUID NOT NULL REFERENCES product(id),
    rank_position INT NOT NULL,
    captured_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_catr_cat_time ON category_ranking(category_id, captured_at DESC);

--changeset nexadrop:005-subscription-plan
CREATE TABLE subscription_plan (
    id UUID PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(1000),
    price_monthly_cents INT DEFAULT 0,
    price_yearly_cents INT DEFAULT 0,
    currency VARCHAR(8) DEFAULT 'USD',
    stripe_monthly_price_id VARCHAR(120),
    stripe_yearly_price_id VARCHAR(120),
    active BOOLEAN DEFAULT TRUE,
    position INT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

--changeset nexadrop:005-plan-feature
CREATE TABLE plan_feature (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES subscription_plan(id) ON DELETE CASCADE,
    feature_key VARCHAR(60) NOT NULL,
    int_value BIGINT,
    bool_value BOOLEAN,
    text_value VARCHAR(400),
    CONSTRAINT ux_planfeat_plan_key UNIQUE (plan_id, feature_key)
);

--changeset nexadrop:005-customer-subscription
CREATE TABLE customer_subscription (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    plan_id UUID NOT NULL REFERENCES subscription_plan(id),
    status VARCHAR(30) NOT NULL,
    billing_period VARCHAR(10) DEFAULT 'MONTHLY',
    stripe_customer_id VARCHAR(120),
    stripe_subscription_id VARCHAR(120),
    current_period_start TIMESTAMP WITH TIME ZONE,
    current_period_end TIMESTAMP WITH TIME ZONE,
    cancel_at TIMESTAMP WITH TIME ZONE,
    canceled_at TIMESTAMP WITH TIME ZONE,
    trial_ends_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_subs_user ON customer_subscription(user_id);

--changeset nexadrop:005-usage-counter
CREATE TABLE usage_counter (
    id UUID PRIMARY KEY,
    subscription_id UUID NOT NULL REFERENCES customer_subscription(id) ON DELETE CASCADE,
    metric VARCHAR(40) NOT NULL,
    period_start TIMESTAMP WITH TIME ZONE NOT NULL,
    period_end TIMESTAMP WITH TIME ZONE NOT NULL,
    value BIGINT DEFAULT 0,
    CONSTRAINT ux_usage_subs_metric_period UNIQUE (subscription_id, metric, period_start)
);

--changeset nexadrop:005-invoice
CREATE TABLE invoice (
    id UUID PRIMARY KEY,
    subscription_id UUID,
    user_id UUID NOT NULL,
    stripe_invoice_id VARCHAR(120),
    amount_cents INT NOT NULL,
    currency VARCHAR(8) DEFAULT 'USD',
    status VARCHAR(30) NOT NULL,
    paid_at TIMESTAMP WITH TIME ZONE,
    pdf_url VARCHAR(800),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

--changeset nexadrop:005-stripe-event
CREATE TABLE stripe_event (
    id UUID PRIMARY KEY,
    stripe_event_id VARCHAR(120) NOT NULL UNIQUE,
    event_type VARCHAR(80) NOT NULL,
    payload TEXT NOT NULL,
    processed BOOLEAN DEFAULT FALSE,
    processed_at TIMESTAMP WITH TIME ZONE,
    error_message VARCHAR(2000),
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

--changeset nexadrop:006-partner-app
CREATE TABLE partner_app (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES users(id),
    name VARCHAR(160) NOT NULL,
    description VARCHAR(1000),
    client_id VARCHAR(80) NOT NULL UNIQUE,
    client_secret_hash VARCHAR(255) NOT NULL,
    scopes VARCHAR(500) DEFAULT 'catalog.read',
    webhook_url VARCHAR(500),
    webhook_secret VARCHAR(120),
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

--changeset nexadrop:006-partner-webhook
CREATE TABLE partner_webhook_delivery (
    id UUID PRIMARY KEY,
    partner_app_id UUID NOT NULL REFERENCES partner_app(id) ON DELETE CASCADE,
    event_type VARCHAR(80) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INT DEFAULT 0,
    last_attempt_at TIMESTAMP WITH TIME ZONE,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    response_code INT,
    response_body VARCHAR(2000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_phook_pending ON partner_webhook_delivery(status, next_attempt_at);

--changeset nexadrop:006-shop-connection
CREATE TABLE shop_connection (
    id UUID PRIMARY KEY,
    partner_app_id UUID NOT NULL REFERENCES partner_app(id) ON DELETE CASCADE,
    platform VARCHAR(40) NOT NULL,
    shop_handle VARCHAR(200) NOT NULL,
    access_token_enc VARCHAR(2000),
    refresh_token_enc VARCHAR(2000),
    settings_json JSONB,
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT ux_shop_platform_handle UNIQUE (platform, shop_handle)
);

--changeset nexadrop:006-product-sync-state
CREATE TABLE product_sync_state (
    id UUID PRIMARY KEY,
    shop_connection_id UUID NOT NULL REFERENCES shop_connection(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES product(id) ON DELETE CASCADE,
    remote_product_id VARCHAR(120),
    status VARCHAR(20) DEFAULT 'PENDING',
    last_synced_at TIMESTAMP WITH TIME ZONE,
    error_message VARCHAR(2000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT ux_psync_shop_product UNIQUE (shop_connection_id, product_id)
);

--changeset nexadrop:007-address
CREATE TABLE address (
    id UUID PRIMARY KEY,
    full_name VARCHAR(200) NOT NULL,
    phone VARCHAR(40),
    email VARCHAR(254),
    line1 VARCHAR(300) NOT NULL,
    line2 VARCHAR(300),
    city VARCHAR(200) NOT NULL,
    state VARCHAR(200),
    postal_code VARCHAR(40),
    country VARCHAR(60) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

--changeset nexadrop:007-order
CREATE TABLE customer_order (
    id UUID PRIMARY KEY,
    order_number VARCHAR(40) NOT NULL UNIQUE,
    partner_app_id UUID REFERENCES partner_app(id),
    user_id UUID,
    external_order_id VARCHAR(120),
    shipping_address_id UUID NOT NULL REFERENCES address(id),
    billing_address_id UUID REFERENCES address(id),
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    subtotal_cents INT DEFAULT 0,
    shipping_cents INT DEFAULT 0,
    tax_cents INT DEFAULT 0,
    total_cents INT DEFAULT 0,
    currency VARCHAR(8) DEFAULT 'USD',
    notes VARCHAR(2000),
    placed_at TIMESTAMP WITH TIME ZONE,
    forwarded_at TIMESTAMP WITH TIME ZONE,
    shipped_at TIMESTAMP WITH TIME ZONE,
    delivered_at TIMESTAMP WITH TIME ZONE,
    cancelled_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_order_status ON customer_order(status);
CREATE INDEX idx_order_partner ON customer_order(partner_app_id);

--changeset nexadrop:007-order-item
CREATE TABLE order_item (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES customer_order(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES product(id),
    variant_id UUID REFERENCES product_variant(id),
    title_snapshot VARCHAR(500),
    image_url_snapshot VARCHAR(800),
    sku_snapshot VARCHAR(120),
    unit_price_cents INT NOT NULL,
    cost_cents INT NOT NULL,
    quantity INT NOT NULL,
    line_total_cents INT NOT NULL
);

--changeset nexadrop:007-shipment
CREATE TABLE shipment (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES customer_order(id) ON DELETE CASCADE,
    carrier VARCHAR(80),
    tracking_number VARCHAR(120),
    tracking_url VARCHAR(800),
    status VARCHAR(30) DEFAULT 'PENDING',
    shipped_at TIMESTAMP WITH TIME ZONE,
    delivered_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

--changeset nexadrop:007-tracking-event
CREATE TABLE tracking_event (
    id UUID PRIMARY KEY,
    shipment_id UUID NOT NULL REFERENCES shipment(id) ON DELETE CASCADE,
    code VARCHAR(40),
    description VARCHAR(500),
    location VARCHAR(200),
    event_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_tevent_shipment_time ON tracking_event(shipment_id, event_at DESC);

--changeset nexadrop:008-crawl-job
CREATE TABLE crawl_job (
    id UUID PRIMARY KEY,
    code VARCHAR(80) NOT NULL UNIQUE,
    kind VARCHAR(40) NOT NULL,
    source VARCHAR(40) NOT NULL,
    config_json JSONB,
    cron_expression VARCHAR(100),
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

--changeset nexadrop:008-crawl-job-run
CREATE TABLE crawl_job_run (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES crawl_job(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    finished_at TIMESTAMP WITH TIME ZONE,
    items_in INT DEFAULT 0,
    items_out INT DEFAULT 0,
    errors INT DEFAULT 0,
    log TEXT
);

--changeset nexadrop:008-outbound-email
CREATE TABLE outbound_email (
    id UUID PRIMARY KEY,
    to_address VARCHAR(254) NOT NULL,
    subject VARCHAR(300) NOT NULL,
    body_html TEXT NOT NULL,
    template VARCHAR(80),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INT DEFAULT 0,
    sent_at TIMESTAMP WITH TIME ZONE,
    error_message VARCHAR(2000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_oemail_pending ON outbound_email(status, created_at);

--changeset nexadrop:008-outbound-event
CREATE TABLE outbound_event (
    id UUID PRIMARY KEY,
    aggregate_id VARCHAR(80) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    payload TEXT NOT NULL,
    topic VARCHAR(100) NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    attempt_count INT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_oevent_pending ON outbound_event(published_at);

--changeset nexadrop:009-seed-plans
INSERT INTO subscription_plan (id, code, name, description, price_monthly_cents, price_yearly_cents, currency, active, position)
VALUES
  (gen_random_uuid(), 'FREE', 'Free', 'Test the API. 100 catalog reads/day.', 0, 0, 'USD', true, 1),
  (gen_random_uuid(), 'STARTER', 'Starter', 'Up to 500 products synced, 5k API requests/day.', 2900, 29000, 'USD', true, 2),
  (gen_random_uuid(), 'PRO', 'Pro', 'Up to 5,000 products synced, 50k API requests/day, priority support.', 9900, 99000, 'USD', true, 3),
  (gen_random_uuid(), 'ENTERPRISE', 'Enterprise', 'Unlimited. Dedicated infra, SLA.', 0, 0, 'USD', true, 4)
ON CONFLICT (code) DO NOTHING;

--changeset nexadrop:009-seed-plan-features
INSERT INTO plan_feature (id, plan_id, feature_key, int_value)
SELECT gen_random_uuid(), p.id, f.key, f.value::bigint
FROM subscription_plan p
CROSS JOIN (VALUES
  ('FREE',       'max_products_sync',        50),
  ('FREE',       'max_api_requests_day',     100),
  ('FREE',       'max_shops',                1),
  ('STARTER',    'max_products_sync',        500),
  ('STARTER',    'max_api_requests_day',     5000),
  ('STARTER',    'max_shops',                3),
  ('PRO',        'max_products_sync',        5000),
  ('PRO',        'max_api_requests_day',     50000),
  ('PRO',        'max_shops',                10),
  ('ENTERPRISE', 'max_products_sync',        1000000),
  ('ENTERPRISE', 'max_api_requests_day',     10000000),
  ('ENTERPRISE', 'max_shops',                100)
) AS f(code, key, value)
WHERE p.code = f.code
ON CONFLICT (plan_id, feature_key) DO NOTHING;

--changeset nexadrop:009-seed-tags
INSERT INTO product_tag (id, slug, name, kind) VALUES
  (gen_random_uuid(), 'bestseller', 'Bestseller', 'BADGE'),
  (gen_random_uuid(), 'new', 'New Arrival', 'BADGE'),
  (gen_random_uuid(), 'trending', 'Trending', 'BADGE'),
  (gen_random_uuid(), 'cross-border', 'Cross-border ready', 'BADGE')
ON CONFLICT (slug) DO NOTHING;

--changeset nexadrop:010-password-reset-token
CREATE TABLE password_reset_token (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_prtok_user ON password_reset_token(user_id);
