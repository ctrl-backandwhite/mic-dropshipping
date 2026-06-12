--liquibase formatted sql
--changeset nexadrop:v32-google-linked
-- Marks whether a user account has a confirmed link to a Google identity.
-- Existing rows default to FALSE: they were created with a local password, so a
-- Google login matching their email must go through a deliberate password
-- confirmation before it is allowed to sign in (prevents account takeover via a
-- verified-but-unowned Google email). Accounts created through Google get TRUE.
ALTER TABLE users ADD COLUMN IF NOT EXISTS google_linked BOOLEAN NOT NULL DEFAULT FALSE;
