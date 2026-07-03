--liquibase formatted sql

--changeset nexadrop:v77-outbound-email-reply-to
ALTER TABLE outbound_email ADD COLUMN IF NOT EXISTS reply_to VARCHAR(254);
