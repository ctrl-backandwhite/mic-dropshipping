--liquibase formatted sql

--changeset nexadrop:v4-perf-indexes-001 splitStatements:true endDelimiter:;
--comment: Performance indexes for high-traffic admin/storefront filters (search, status, dates).

-- Orders: filter by status + sort by placed_at; lookup by user_id
CREATE INDEX IF NOT EXISTS idx_customer_order_status        ON customer_order(status);
CREATE INDEX IF NOT EXISTS idx_customer_order_placed_at     ON customer_order(placed_at DESC);
CREATE INDEX IF NOT EXISTS idx_customer_order_user_id       ON customer_order(user_id);
CREATE INDEX IF NOT EXISTS idx_customer_order_partner_app   ON customer_order(partner_app_id);
CREATE INDEX IF NOT EXISTS idx_customer_order_external_id   ON customer_order(external_order_id);

-- Order items: lookup all items by order, by product
CREATE INDEX IF NOT EXISTS idx_order_item_order             ON order_item(order_id);
CREATE INDEX IF NOT EXISTS idx_order_item_product           ON order_item(product_id);

-- Products: storefront filters (status + sort), supplier/category lookups, trending
CREATE INDEX IF NOT EXISTS idx_product_status               ON product(status);
CREATE INDEX IF NOT EXISTS idx_product_supplier             ON product(supplier_id);
CREATE INDEX IF NOT EXISTS idx_product_category             ON product(category_id);
CREATE INDEX IF NOT EXISTS idx_product_trend_score          ON product(trend_score DESC NULLS LAST);
CREATE INDEX IF NOT EXISTS idx_product_monthly_sales        ON product(monthly_sales DESC NULLS LAST);
-- Case-insensitive search on titles (Spanish and Chinese)
CREATE INDEX IF NOT EXISTS idx_product_title_zh_lower       ON product(LOWER(title_zh));

-- Product translations: locate by language for the storefront
CREATE INDEX IF NOT EXISTS idx_product_translation_lang     ON product_translation(language);
CREATE INDEX IF NOT EXISTS idx_product_translation_title    ON product_translation(LOWER(title));

-- Users: lookup by role + country for filters; case-insensitive email already covered by unique
CREATE INDEX IF NOT EXISTS idx_user_role                    ON users(role);
CREATE INDEX IF NOT EXISTS idx_user_country                 ON users(country);
CREATE INDEX IF NOT EXISTS idx_user_display_name_lower      ON users(LOWER(display_name));
CREATE INDEX IF NOT EXISTS idx_user_company_lower           ON users(LOWER(company_name));

-- User addresses: list by owner (default first)
CREATE INDEX IF NOT EXISTS idx_user_address_user            ON user_address(user_id, is_default DESC, created_at DESC);

-- Payments: history & status reports
CREATE INDEX IF NOT EXISTS idx_payment_user                 ON payment(user_id);
CREATE INDEX IF NOT EXISTS idx_payment_status               ON payment(status);
CREATE INDEX IF NOT EXISTS idx_payment_created              ON payment(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_payment_provider_ref         ON payment(provider_ref);

-- Wallet transactions: ledger per wallet, time-ordered
CREATE INDEX IF NOT EXISTS idx_wallet_tx_wallet_created     ON wallet_transaction(wallet_id, created_at DESC);

-- Subscriptions: filter by status / user
CREATE INDEX IF NOT EXISTS idx_subscription_status          ON customer_subscription(status);
CREATE INDEX IF NOT EXISTS idx_subscription_user            ON customer_subscription(user_id);

-- Price rules: most-specific-wins resolution
CREATE INDEX IF NOT EXISTS idx_price_rule_scope             ON price_rule(scope, scope_id, position);

-- Suppliers: country filter + name search
CREATE INDEX IF NOT EXISTS idx_supplier_country             ON supplier(country);
CREATE INDEX IF NOT EXISTS idx_supplier_name_lower          ON supplier(LOWER(name));
