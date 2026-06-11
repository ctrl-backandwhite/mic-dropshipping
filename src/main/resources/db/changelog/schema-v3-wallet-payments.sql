--liquibase formatted sql

--changeset nexadrop:v3-wallet
CREATE TABLE wallet (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    balance_usd_cents BIGINT NOT NULL DEFAULT 0,
    hold_usd_cents BIGINT NOT NULL DEFAULT 0,
    currency_default VARCHAR(8) DEFAULT 'USD',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE TABLE wallet_transaction (
    id UUID PRIMARY KEY,
    wallet_id UUID NOT NULL REFERENCES wallet(id) ON DELETE CASCADE,
    kind VARCHAR(20) NOT NULL,                  -- DEPOSIT | WITHDRAW | PAYMENT | REFUND | HOLD | RELEASE | ADJUSTMENT
    amount_usd_cents BIGINT NOT NULL,           -- signed: positive credit, negative debit
    balance_after_cents BIGINT NOT NULL,
    payment_id UUID,
    order_id UUID,
    idempotency_key VARCHAR(128),
    status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    description VARCHAR(500),
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_wtx_wallet_time ON wallet_transaction(wallet_id, created_at DESC);
CREATE UNIQUE INDEX ux_wtx_idempotency ON wallet_transaction(idempotency_key) WHERE idempotency_key IS NOT NULL;

--changeset nexadrop:v3-payment
CREATE TABLE payment (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    wallet_id UUID REFERENCES wallet(id) ON DELETE SET NULL,
    method VARCHAR(20) NOT NULL,                -- CARD | PAYPAL | USDT
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    amount_display NUMERIC(14,4),
    currency_display VARCHAR(8),
    amount_usd_cents BIGINT NOT NULL,
    settlement_currency VARCHAR(10) NOT NULL,
    settlement_amount NUMERIC(14,4),
    provider VARCHAR(40),                       -- stripe | paypal | coinbase | manual
    provider_ref VARCHAR(255),                  -- pi_xxx | paypal order id | charge code
    provider_response JSONB,
    idempotency_key VARCHAR(128) UNIQUE,
    crypto_address VARCHAR(255),
    crypto_chain VARCHAR(20),                   -- TRC20 | ERC20 | BEP20
    crypto_expires_at TIMESTAMP WITH TIME ZONE,
    qr_url VARCHAR(800),
    error_message VARCHAR(2000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_payment_user_status ON payment(user_id, status);
CREATE INDEX idx_payment_provider_ref ON payment(provider, provider_ref);

--changeset nexadrop:v3-idempotency
CREATE TABLE idempotency_record (
    key VARCHAR(128) PRIMARY KEY,
    user_id UUID,
    endpoint VARCHAR(200) NOT NULL,
    method VARCHAR(10) NOT NULL,
    response_status INT NOT NULL,
    response_body TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_idem_user_time ON idempotency_record(user_id, created_at DESC);

--changeset nexadrop:v3-user-address
CREATE TABLE user_address (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    label VARCHAR(80),
    full_name VARCHAR(200) NOT NULL,
    phone VARCHAR(40),
    line1 VARCHAR(300) NOT NULL,
    line2 VARCHAR(300),
    city VARCHAR(200) NOT NULL,
    state VARCHAR(200),
    postal_code VARCHAR(40),
    country VARCHAR(60) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_useraddr_user ON user_address(user_id);

--changeset nexadrop:v3-totp-secret
CREATE TABLE totp_secret (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    secret_enc VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    last_used_at TIMESTAMP WITH TIME ZONE,
    recovery_codes_hash TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

--changeset nexadrop:v3-create-wallets-for-existing-users
INSERT INTO wallet (id, user_id, balance_usd_cents, hold_usd_cents, currency_default, status)
SELECT gen_random_uuid(), id, 0, 0, COALESCE(language, 'en'), 'ACTIVE'
FROM users
WHERE id NOT IN (SELECT user_id FROM wallet);
