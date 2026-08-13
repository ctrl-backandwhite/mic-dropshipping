--liquibase formatted sql

--changeset nexa:v114-plan-pending-downgrade
-- Bajada de plan programada a fin de periodo: se mantiene el plan ACTUAL (con todas sus características)
-- hasta el final del mes ya pagado y, en la renovación, se cobra y aplica el plan MENOR. Estas columnas
-- guardan el cambio pendiente hasta que un barrido lo aplica al llegar la fecha.
ALTER TABLE customer_subscription ADD COLUMN IF NOT EXISTS pending_plan_code varchar(40);
ALTER TABLE customer_subscription ADD COLUMN IF NOT EXISTS pending_plan_at timestamptz;
