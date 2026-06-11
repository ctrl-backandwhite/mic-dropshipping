--liquibase formatted sql

--changeset nexadrop:v8b-updated-at-001 splitStatements:true endDelimiter:;
--comment: BaseEntity requires updated_at on every JPA-managed table.

ALTER TABLE user_shop_connection    ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE shop_product_listing    ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE sourcing_request        ADD COLUMN IF NOT EXISTS updated_at_dummy boolean DEFAULT true; -- already has updated_at
ALTER TABLE sourcing_quote          ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE agent_profile           ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE pod_design              ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE odm_project             ADD COLUMN IF NOT EXISTS updated_at_dummy boolean DEFAULT true; -- already has updated_at
ALTER TABLE intelligence_alert      ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE academy_course          ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE academy_enrollment      ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE mentor_profile          ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE mentor_booking          ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE affiliate               ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE affiliate_referral      ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE support_ticket          ADD COLUMN IF NOT EXISTS updated_at_dummy boolean DEFAULT true; -- already has updated_at
ALTER TABLE support_ticket_reply    ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE notification            ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE warehouse               ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE product_warehouse_stock ADD COLUMN IF NOT EXISTS updated_at_dummy boolean DEFAULT true; -- already has updated_at
