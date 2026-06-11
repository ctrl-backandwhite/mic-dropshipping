--liquibase formatted sql

--changeset nexadrop:v2-currency-rate
CREATE TABLE currency_rate (
    id UUID PRIMARY KEY,
    code VARCHAR(8) NOT NULL UNIQUE,
    name VARCHAR(80) NOT NULL,
    symbol VARCHAR(8) NOT NULL,
    country_code VARCHAR(4),
    flag_emoji VARCHAR(8),
    locale VARCHAR(12),
    rate_vs_usd NUMERIC(18,8) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    last_synced_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_currency_active ON currency_rate(active);

--changeset nexadrop:v2-currency-seed
INSERT INTO currency_rate (id, code, name, symbol, country_code, flag_emoji, locale, rate_vs_usd, active) VALUES
  (gen_random_uuid(), 'USD', 'US Dollar',              '$',   'US', '🇺🇸', 'en-US', 1.00,        true),
  (gen_random_uuid(), 'EUR', 'Euro',                   '€',   'EU', '🇪🇺', 'es-ES', 0.92,        true),
  (gen_random_uuid(), 'GBP', 'British Pound',          '£',   'GB', '🇬🇧', 'en-GB', 0.79,        true),
  (gen_random_uuid(), 'JPY', 'Japanese Yen',           '¥',   'JP', '🇯🇵', 'ja-JP', 156.4,       true),
  (gen_random_uuid(), 'CNY', 'Chinese Yuan',           '¥',   'CN', '🇨🇳', 'zh-CN', 7.24,        true),
  (gen_random_uuid(), 'MXN', 'Mexican Peso',           '$',   'MX', '🇲🇽', 'es-MX', 17.10,       true),
  (gen_random_uuid(), 'COP', 'Colombian Peso',         '$',   'CO', '🇨🇴', 'es-CO', 3950.00,     true),
  (gen_random_uuid(), 'BRL', 'Brazilian Real',         'R$',  'BR', '🇧🇷', 'pt-BR', 5.12,        true),
  (gen_random_uuid(), 'ARS', 'Argentine Peso',         '$',   'AR', '🇦🇷', 'es-AR', 890.00,      true),
  (gen_random_uuid(), 'CLP', 'Chilean Peso',           '$',   'CL', '🇨🇱', 'es-CL', 920.00,      true),
  (gen_random_uuid(), 'PEN', 'Peruvian Sol',           'S/',  'PE', '🇵🇪', 'es-PE', 3.74,        true),
  (gen_random_uuid(), 'AUD', 'Australian Dollar',      'A$',  'AU', '🇦🇺', 'en-AU', 1.52,        true),
  (gen_random_uuid(), 'CAD', 'Canadian Dollar',        'C$',  'CA', '🇨🇦', 'en-CA', 1.36,        true),
  (gen_random_uuid(), 'INR', 'Indian Rupee',           '₹',   'IN', '🇮🇳', 'en-IN', 83.20,       true),
  (gen_random_uuid(), 'KRW', 'South Korean Won',       '₩',   'KR', '🇰🇷', 'ko-KR', 1340.00,     true),
  (gen_random_uuid(), 'SGD', 'Singapore Dollar',       'S$',  'SG', '🇸🇬', 'en-SG', 1.35,        true),
  (gen_random_uuid(), 'HKD', 'Hong Kong Dollar',       'HK$', 'HK', '🇭🇰', 'zh-HK', 7.82,        true),
  (gen_random_uuid(), 'CHF', 'Swiss Franc',            'Fr',  'CH', '🇨🇭', 'de-CH', 0.91,        true),
  (gen_random_uuid(), 'SEK', 'Swedish Krona',          'kr',  'SE', '🇸🇪', 'sv-SE', 10.50,       true),
  (gen_random_uuid(), 'NOK', 'Norwegian Krone',        'kr',  'NO', '🇳🇴', 'nb-NO', 10.80,       true),
  (gen_random_uuid(), 'DKK', 'Danish Krone',           'kr',  'DK', '🇩🇰', 'da-DK', 6.87,        true),
  (gen_random_uuid(), 'PLN', 'Polish Zloty',           'zł',  'PL', '🇵🇱', 'pl-PL', 3.95,        true),
  (gen_random_uuid(), 'TRY', 'Turkish Lira',           '₺',   'TR', '🇹🇷', 'tr-TR', 32.00,       true),
  (gen_random_uuid(), 'ZAR', 'South African Rand',     'R',   'ZA', '🇿🇦', 'en-ZA', 18.50,       true),
  (gen_random_uuid(), 'AED', 'UAE Dirham',             'د.إ', 'AE', '🇦🇪', 'ar-AE', 3.67,        true)
ON CONFLICT (code) DO NOTHING;

--changeset nexadrop:v2-product-usd-columns
ALTER TABLE product       ADD COLUMN IF NOT EXISTS cost_usd       NUMERIC(12,4);
ALTER TABLE product       ADD COLUMN IF NOT EXISTS retail_usd     NUMERIC(12,4);
ALTER TABLE product_variant      ADD COLUMN IF NOT EXISTS cost_usd       NUMERIC(12,4);
ALTER TABLE product_variant      ADD COLUMN IF NOT EXISTS retail_usd     NUMERIC(12,4);
ALTER TABLE product_price_tier   ADD COLUMN IF NOT EXISTS unit_price_usd NUMERIC(12,4);
ALTER TABLE customer_order       ADD COLUMN IF NOT EXISTS subtotal_usd_cents INT;
ALTER TABLE customer_order       ADD COLUMN IF NOT EXISTS total_usd_cents    INT;
ALTER TABLE customer_order       ADD COLUMN IF NOT EXISTS display_currency   VARCHAR(8) DEFAULT 'USD';

--changeset nexadrop:v2-price-rule
CREATE TABLE price_rule (
    id UUID PRIMARY KEY,
    scope VARCHAR(20) NOT NULL,            -- GLOBAL | CATEGORY | SUPPLIER | PRODUCT | VARIANT
    scope_id UUID,                          -- nullable for GLOBAL
    margin_type VARCHAR(20) NOT NULL,      -- PERCENTAGE | FIXED
    margin_value NUMERIC(12,4) NOT NULL,   -- percent (e.g. 35.0) or USD fixed amount
    min_cost_usd NUMERIC(12,4),
    max_cost_usd NUMERIC(12,4),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    position INT NOT NULL DEFAULT 0,
    description VARCHAR(300),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_pricerule_scope ON price_rule(scope, active);

--changeset nexadrop:v2-price-rule-seed
INSERT INTO price_rule (id, scope, scope_id, margin_type, margin_value, active, position, description) VALUES
  (gen_random_uuid(), 'GLOBAL',   NULL, 'PERCENTAGE', 35.00, true, 0, 'Default markup for any product without a more specific rule')
ON CONFLICT DO NOTHING;
