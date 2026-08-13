--liquibase formatted sql

--changeset nexa:v116-user-phone-unique
-- El teléfono debe ser ÚNICO en toda la aplicación: no puede estar registrado en dos cuentas. Índice
-- parcial: solo aplica a cuentas activas con teléfono (los borrados/anonimizados con deleted_at quedan fuera
-- y los null no colisionan entre sí). La violación se humaniza vía ConstraintMessage(uk_users_phone).
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_phone
  ON users (phone)
  WHERE phone IS NOT NULL AND deleted_at IS NULL;
