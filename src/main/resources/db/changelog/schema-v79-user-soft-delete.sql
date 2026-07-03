--liquibase formatted sql

--changeset nexadrop:v79-user-soft-delete
-- BORRADO LÓGICO (soft delete) de la cuenta por el propio usuario, confirmado con un CÓDIGO enviado a su
-- email. deleted_at marca la baja (la fila NO se borra físicamente); deletion_code + su expiración guardan
-- el código de confirmación temporal (6 dígitos, 30 min). El login ya bloquea las cuentas con active=false.
ALTER TABLE users ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS deletion_code VARCHAR(64);
ALTER TABLE users ADD COLUMN IF NOT EXISTS deletion_code_expires_at TIMESTAMP;
