--liquibase formatted sql

--changeset nexa:v115-pm-delete-code
-- Eliminar un método de pago exige confirmar con un código enviado por correo. Se guarda el código, la
-- referencia del método a borrar y su vencimiento en la propia cuenta (un solo borrado pendiente a la vez).
ALTER TABLE users ADD COLUMN IF NOT EXISTS pm_delete_code varchar(12);
ALTER TABLE users ADD COLUMN IF NOT EXISTS pm_delete_ref varchar(200);
ALTER TABLE users ADD COLUMN IF NOT EXISTS pm_delete_code_at timestamptz;
