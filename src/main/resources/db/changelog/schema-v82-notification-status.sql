--liquibase formatted sql
--changeset nexadrop:v82-notification-status
-- Flujo de gestión tipo ticket para las notificaciones accionables (solicitudes de contacto/soporte):
--   NEW → RECEIVED → IN_PROGRESS ⇄ WAITING → RESOLVED
-- RECEIVED se marca automáticamente al abrir la notificación; el resto son transiciones manuales del gestor.
-- Las notificaciones no accionables (broadcast/sistema) se quedan en NEW y no muestran el flujo en la UI.

ALTER TABLE notification ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'NEW';

CREATE INDEX IF NOT EXISTS idx_notification_status ON notification (user_id, status);
