--liquibase formatted sql
--changeset nexadrop:v76-user-name-parts
-- Nombre del usuario en partes separadas: nombre + primer apellido + segundo apellido.
-- El display_name se mantiene como el nombre completo concatenado (compatibilidad con nav/emails/perfil).
ALTER TABLE users ADD COLUMN IF NOT EXISTS first_name   VARCHAR(80);
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_name_1  VARCHAR(80);
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_name_2  VARCHAR(80);
