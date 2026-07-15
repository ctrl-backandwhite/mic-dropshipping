--liquibase formatted sql
--changeset nexadrop:v84-product-verified
-- Verificación manual del admin por producto: false por defecto (pendiente/con error), true = revisado OK.
ALTER TABLE product ADD COLUMN IF NOT EXISTS verified BOOLEAN NOT NULL DEFAULT FALSE;
