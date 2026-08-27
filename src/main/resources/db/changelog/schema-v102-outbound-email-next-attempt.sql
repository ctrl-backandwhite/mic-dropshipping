--liquibase formatted sql

--changeset nexadrop:v102-outbound-email-next-attempt splitStatements:false
-- Reintento diferido de correos salientes. Antes el barrido reintentaba cada 15 s y descartaba tras 5
-- intentos (~1 min): con un rate-limit horario del proveedor (Hostinger devuelve
-- "451 4.7.1 Ratelimit exceeded") los 5 intentos caían en la misma ventana de bloqueo y el correo se
-- perdía. Ahora un fallo temporal aplaza el siguiente intento en esta columna (backoff creciente) y se
-- reintenta hasta 24 h; solo los fallos permanentes (5xx) se descartan de inmediato.
ALTER TABLE outbound_email ADD COLUMN IF NOT EXISTS next_attempt_at timestamptz;

-- El barrido busca PENDING con next_attempt_at nulo o vencido, ordenados por antigüedad.
CREATE INDEX IF NOT EXISTS idx_outbound_email_dispatch
    ON outbound_email (status, next_attempt_at, created_at);
