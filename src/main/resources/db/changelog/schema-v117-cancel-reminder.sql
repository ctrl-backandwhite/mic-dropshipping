--liquibase formatted sql

--changeset nexa:v117-cancel-reminder
-- Recordatorio de cancelación de plan: los 3 días previos a la fecha de cancelación se envía un email al
-- día (máx 3). Esta marca guarda el instante del último recordatorio enviado para no repetir en el mismo
-- día y para parar si el cliente renueva (deja de estar en cancelación → cancel_at null).
ALTER TABLE customer_subscription ADD COLUMN IF NOT EXISTS cancel_reminder_last_at timestamptz;
