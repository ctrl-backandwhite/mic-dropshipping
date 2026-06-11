--liquibase formatted sql

--changeset nexadrop:v8-platform-modules-001 splitStatements:true endDelimiter:;
--comment: Tablas para las épicas DROP-2..13 (sourcing, shops, POD, ODM, intelligence, academy, mentors, affiliates, tickets, notifications, warehouses).

-- product flags para DROP-13 + DROP-2
ALTER TABLE product
    ADD COLUMN IF NOT EXISTS pod_enabled        boolean DEFAULT false,
    ADD COLUMN IF NOT EXISTS brand_selected     boolean DEFAULT false,
    ADD COLUMN IF NOT EXISTS ready_to_ship      boolean DEFAULT false,
    ADD COLUMN IF NOT EXISTS ar_model_url       varchar(800),
    ADD COLUMN IF NOT EXISTS reviews_summary    text,
    ADD COLUMN IF NOT EXISTS reviews_sentiment  numeric(3,2);

-- DROP-3 / DROP-15 — sourcing
CREATE TABLE IF NOT EXISTS sourcing_request (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid REFERENCES users(id) ON DELETE SET NULL,
    source       varchar(40),
    external_id  varchar(120),
    source_url   varchar(800) NOT NULL,
    title_hint   varchar(400),
    status       varchar(20) NOT NULL DEFAULT 'PENDING',  -- PENDING|QUOTING|APPROVED|REJECTED|FULFILLED
    plan_quota   varchar(20),
    notes        varchar(2000),
    selected_quote_id uuid,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_sourcing_req_user   ON sourcing_request(user_id);
CREATE INDEX IF NOT EXISTS idx_sourcing_req_status ON sourcing_request(status);

CREATE TABLE IF NOT EXISTS agent_profile (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        uuid REFERENCES users(id) ON DELETE CASCADE,
    display_name   varchar(120) NOT NULL,
    tier           varchar(20)  NOT NULL,  -- JUNIOR | MID | SENIOR | STRATEGIC
    bio            varchar(1000),
    avatar_url     varchar(800),
    languages      jsonb NOT NULL DEFAULT '[]'::jsonb,
    success_rate   numeric(5,2) DEFAULT 0,
    avg_response_hours numeric(6,2) DEFAULT 0,
    satisfaction   numeric(3,2) DEFAULT 0,
    completed_jobs int NOT NULL DEFAULT 0,
    hourly_rate_usd_cents int,
    active         boolean NOT NULL DEFAULT true,
    created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_agent_tier   ON agent_profile(tier);
CREATE INDEX IF NOT EXISTS idx_agent_active ON agent_profile(active);

CREATE TABLE IF NOT EXISTS sourcing_quote (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id    uuid NOT NULL REFERENCES sourcing_request(id) ON DELETE CASCADE,
    agent_id      uuid REFERENCES agent_profile(id) ON DELETE SET NULL,
    price_usd_cents int NOT NULL,
    eta_days      int NOT NULL,
    moq           int,
    notes         varchar(2000),
    status        varchar(20) NOT NULL DEFAULT 'OPEN',  -- OPEN | ACCEPTED | REJECTED | EXPIRED
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_quote_request ON sourcing_quote(request_id);
CREATE INDEX IF NOT EXISTS idx_quote_agent   ON sourcing_quote(agent_id);

-- DROP-5 — shop connections
CREATE TABLE IF NOT EXISTS user_shop_connection (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    platform        varchar(40) NOT NULL,  -- shopify | woocommerce | tiktokshop | ebay | amazon | bigcommerce | wix | squarespace | magento | lazada | shopee
    shop_handle     varchar(180) NOT NULL,
    access_token_enc varchar(2000),
    status          varchar(20)  NOT NULL DEFAULT 'CONNECTED',  -- CONNECTED | DISCONNECTED | ERROR
    last_sync_at    timestamptz,
    metadata        jsonb DEFAULT '{}'::jsonb,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_shop_user     ON user_shop_connection(user_id);
CREATE INDEX IF NOT EXISTS idx_shop_platform ON user_shop_connection(platform);

-- DROP-5 — product listing across platforms
CREATE TABLE IF NOT EXISTS shop_product_listing (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_shop_connection_id uuid NOT NULL REFERENCES user_shop_connection(id) ON DELETE CASCADE,
    product_id        uuid NOT NULL REFERENCES product(id) ON DELETE CASCADE,
    remote_product_id varchar(180),
    status            varchar(20) NOT NULL DEFAULT 'DRAFT',  -- DRAFT | LISTED | ERROR
    last_pushed_at    timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_shop_connection_id, product_id)
);
CREATE INDEX IF NOT EXISTS idx_listing_shop    ON shop_product_listing(user_shop_connection_id);
CREATE INDEX IF NOT EXISTS idx_listing_product ON shop_product_listing(product_id);

-- DROP-6 — Print on Demand designs
CREATE TABLE IF NOT EXISTS pod_design (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    product_id   uuid NOT NULL REFERENCES product(id) ON DELETE CASCADE,
    name         varchar(200) NOT NULL,
    canvas_json  jsonb NOT NULL DEFAULT '{}'::jsonb,
    mockup_url   varchar(800),
    status       varchar(20) NOT NULL DEFAULT 'DRAFT',  -- DRAFT | RENDERED | PUBLISHED
    ai_prompt    varchar(1000),
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_pod_design_user    ON pod_design(user_id);
CREATE INDEX IF NOT EXISTS idx_pod_design_product ON pod_design(product_id);

-- DROP-7 — ODM/OEM/Custom Packaging
CREATE TABLE IF NOT EXISTS odm_project (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind         varchar(20) NOT NULL,  -- ODM_FREE | ODM_PAID | OEM | CUSTOM_PACKAGING
    title        varchar(200) NOT NULL,
    brief        text,
    budget_usd_cents int,
    sla_days     int,
    status       varchar(20) NOT NULL DEFAULT 'INTAKE',  -- INTAKE | QUOTING | IN_DESIGN | SAMPLING | PRODUCTION | DELIVERED | CANCELLED
    assigned_to  uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_odm_user   ON odm_project(user_id);
CREATE INDEX IF NOT EXISTS idx_odm_status ON odm_project(status);
CREATE INDEX IF NOT EXISTS idx_odm_kind   ON odm_project(kind);

-- DROP-8 — Intelligence (ad/sales trends + alerts)
CREATE TABLE IF NOT EXISTS ad_trend (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source        varchar(20) NOT NULL,  -- tiktok | facebook | instagram | pinterest | youtube | amazon
    headline      varchar(300) NOT NULL,
    product_slug  varchar(220),
    impressions   bigint,
    engagement    bigint,
    score         numeric(6,3),
    region        varchar(2),
    captured_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ad_trend_source ON ad_trend(source);
CREATE INDEX IF NOT EXISTS idx_ad_trend_score  ON ad_trend(score DESC NULLS LAST);
CREATE INDEX IF NOT EXISTS idx_ad_trend_when   ON ad_trend(captured_at DESC);

CREATE TABLE IF NOT EXISTS intelligence_alert (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    keyword       varchar(200),
    category_id   uuid REFERENCES category(id) ON DELETE SET NULL,
    channel       varchar(20) NOT NULL DEFAULT 'EMAIL',  -- EMAIL | IN_APP | PUSH
    threshold_score numeric(6,3),
    active        boolean NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_intel_alert_user ON intelligence_alert(user_id);

-- DROP-10 — Academy + Mentors + Affiliates + Community
CREATE TABLE IF NOT EXISTS academy_course (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug          varchar(220) NOT NULL UNIQUE,
    title         varchar(200) NOT NULL,
    description   text,
    instructor    varchar(120),
    duration_minutes int,
    cover_url     varchar(800),
    video_url     varchar(800),
    locale        varchar(8) NOT NULL DEFAULT 'es',
    level         varchar(20) NOT NULL DEFAULT 'BEGINNER',  -- BEGINNER | INTERMEDIATE | ADVANCED
    published     boolean NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_course_locale ON academy_course(locale);
CREATE INDEX IF NOT EXISTS idx_course_level  ON academy_course(level);

CREATE TABLE IF NOT EXISTS academy_enrollment (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    course_id   uuid NOT NULL REFERENCES academy_course(id) ON DELETE CASCADE,
    progress_pct numeric(5,2) NOT NULL DEFAULT 0,
    completed_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, course_id)
);
CREATE INDEX IF NOT EXISTS idx_enrollment_user ON academy_enrollment(user_id);

CREATE TABLE IF NOT EXISTS mentor_profile (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    headline      varchar(200) NOT NULL,
    expertise     jsonb NOT NULL DEFAULT '[]'::jsonb,
    languages     jsonb NOT NULL DEFAULT '[]'::jsonb,
    hourly_rate_usd_cents int NOT NULL DEFAULT 0,
    bio           text,
    timezone      varchar(40),
    active        boolean NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id)
);
CREATE INDEX IF NOT EXISTS idx_mentor_active ON mentor_profile(active);

CREATE TABLE IF NOT EXISTS mentor_booking (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    mentor_id     uuid NOT NULL REFERENCES mentor_profile(id) ON DELETE CASCADE,
    learner_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    starts_at     timestamptz NOT NULL,
    duration_min  int NOT NULL DEFAULT 60,
    status        varchar(20) NOT NULL DEFAULT 'REQUESTED',  -- REQUESTED | CONFIRMED | COMPLETED | CANCELLED
    topic         varchar(300),
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_booking_mentor  ON mentor_booking(mentor_id);
CREATE INDEX IF NOT EXISTS idx_booking_learner ON mentor_booking(learner_id);

CREATE TABLE IF NOT EXISTS affiliate (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    code          varchar(40)  NOT NULL UNIQUE,
    earnings_usd_cents bigint NOT NULL DEFAULT 0,
    payout_usd_cents   bigint NOT NULL DEFAULT 0,
    referrals_count    int    NOT NULL DEFAULT 0,
    active        boolean NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS affiliate_referral (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    affiliate_id  uuid NOT NULL REFERENCES affiliate(id) ON DELETE CASCADE,
    referred_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    commission_usd_cents bigint NOT NULL DEFAULT 0,
    converted     boolean NOT NULL DEFAULT false,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_referral_affiliate ON affiliate_referral(affiliate_id);

-- DROP-11 — Support tickets + Disputes + Notifications
CREATE TABLE IF NOT EXISTS support_ticket (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind          varchar(20)  NOT NULL,  -- SUPPORT | DISPUTE
    subject       varchar(300) NOT NULL,
    body          text,
    order_id      uuid REFERENCES customer_order(id) ON DELETE SET NULL,
    status        varchar(20)  NOT NULL DEFAULT 'OPEN',  -- OPEN | WAITING | RESOLVED | CLOSED
    priority      varchar(10)  NOT NULL DEFAULT 'NORMAL', -- LOW | NORMAL | HIGH | URGENT
    assigned_to   uuid REFERENCES users(id) ON DELETE SET NULL,
    resolution    text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ticket_user   ON support_ticket(user_id);
CREATE INDEX IF NOT EXISTS idx_ticket_kind   ON support_ticket(kind);
CREATE INDEX IF NOT EXISTS idx_ticket_status ON support_ticket(status);

CREATE TABLE IF NOT EXISTS support_ticket_reply (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id   uuid NOT NULL REFERENCES support_ticket(id) ON DELETE CASCADE,
    author_id   uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    body        text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ticket_reply_ticket ON support_ticket_reply(ticket_id);

CREATE TABLE IF NOT EXISTS notification (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    event_type  varchar(60) NOT NULL,
    title       varchar(300) NOT NULL,
    body        text,
    channel     varchar(20) NOT NULL DEFAULT 'IN_APP',  -- IN_APP | EMAIL | PUSH
    payload     jsonb DEFAULT '{}'::jsonb,
    read_at     timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_notification_user_unread ON notification(user_id, read_at);
CREATE INDEX IF NOT EXISTS idx_notification_created     ON notification(created_at DESC);

-- DROP-13 — Warehouses + per-warehouse stock + ESG
CREATE TABLE IF NOT EXISTS warehouse (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code         varchar(20)  NOT NULL UNIQUE,    -- CN-SHZ, US-LAX, ES-MAD…
    name         varchar(120) NOT NULL,
    country      varchar(2)   NOT NULL,
    city         varchar(120),
    active       boolean NOT NULL DEFAULT true,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_warehouse_country ON warehouse(country);

CREATE TABLE IF NOT EXISTS product_warehouse_stock (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id    uuid NOT NULL REFERENCES product(id) ON DELETE CASCADE,
    warehouse_id  uuid NOT NULL REFERENCES warehouse(id) ON DELETE CASCADE,
    stock         int NOT NULL DEFAULT 0,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (product_id, warehouse_id)
);
CREATE INDEX IF NOT EXISTS idx_pws_product   ON product_warehouse_stock(product_id);
CREATE INDEX IF NOT EXISTS idx_pws_warehouse ON product_warehouse_stock(warehouse_id);
