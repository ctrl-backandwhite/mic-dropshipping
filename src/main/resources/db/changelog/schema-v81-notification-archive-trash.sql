--liquibase formatted sql
--changeset nexadrop:v81-notification-archive-trash
-- Buzón de notificaciones estilo email: archivar (archived_at) y papelera / borrado lógico (deleted_at).
-- Recibidos = ambos NULL; Archivados = deleted_at NULL y archived_at NOT NULL; Papelera = deleted_at NOT NULL.
-- El borrado definitivo sí elimina la fila (DELETE físico desde la papelera).

ALTER TABLE notification ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ;
ALTER TABLE notification ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_notification_user_state
    ON notification (user_id, deleted_at, archived_at, created_at DESC);
