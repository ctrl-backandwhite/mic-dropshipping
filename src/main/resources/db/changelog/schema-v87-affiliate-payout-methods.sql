-- schema-v87: métodos de cobro del afiliado (wallet/banco/PayPal) + snapshot de destino en el payout
ALTER TABLE affiliate ADD COLUMN IF NOT EXISTS payout_method VARCHAR(20) NOT NULL DEFAULT 'WALLET';
ALTER TABLE affiliate ADD COLUMN IF NOT EXISTS bank_holder  VARCHAR(160);
ALTER TABLE affiliate ADD COLUMN IF NOT EXISTS bank_iban    VARCHAR(40);
ALTER TABLE affiliate ADD COLUMN IF NOT EXISTS bank_bic     VARCHAR(16);
ALTER TABLE affiliate ADD COLUMN IF NOT EXISTS paypal_email VARCHAR(200);

ALTER TABLE affiliate_payout ADD COLUMN IF NOT EXISTS dest_holder       VARCHAR(160);
ALTER TABLE affiliate_payout ADD COLUMN IF NOT EXISTS dest_iban         VARCHAR(40);
ALTER TABLE affiliate_payout ADD COLUMN IF NOT EXISTS dest_bic          VARCHAR(16);
ALTER TABLE affiliate_payout ADD COLUMN IF NOT EXISTS dest_paypal_email VARCHAR(200);
ALTER TABLE affiliate_payout ADD COLUMN IF NOT EXISTS paid_reference    VARCHAR(200);
ALTER TABLE affiliate_payout ADD COLUMN IF NOT EXISTS paid_by           UUID;
