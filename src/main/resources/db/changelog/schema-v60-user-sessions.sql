--liquibase formatted sql

--changeset nexadrop:v60-user-sessions
-- Dispositivos/sesiones conectados del usuario: una fila por login (con dispositivo, IP y last-seen),
-- identificada por un token de dispositivo (cookie nx_device). Permite listar y revocar sesiones.
CREATE TABLE IF NOT EXISTS user_session (
    id           uuid PRIMARY KEY,
    user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_token varchar(120) NOT NULL,           -- valor de la cookie nx_device (identifica el dispositivo)
    device       varchar(160),                     -- etiqueta legible: "Chrome · Windows"
    user_agent   varchar(400),
    ip           varchar(64),
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    revoked_at   timestamptz
);
CREATE INDEX IF NOT EXISTS idx_user_session_token ON user_session(device_token);
CREATE INDEX IF NOT EXISTS idx_user_session_user  ON user_session(user_id, last_seen_at DESC);
