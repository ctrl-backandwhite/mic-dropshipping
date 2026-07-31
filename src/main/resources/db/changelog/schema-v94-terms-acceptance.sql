--liquibase formatted sql

--changeset nexadrop:v94-terms-acceptance splitStatements:false
-- Constancia de qué aceptó cada usuario y cuándo.
--
-- Hasta ahora los términos decían «al crear una cuenta aceptas estas condiciones» y no había nada más:
-- ni casilla, ni registro. Como aceptación por conducta puede sostenerse, pero no permite acreditar QUÉ
-- versión del texto aceptó cada persona ni en qué momento, que es exactamente lo que hay que poder
-- demostrar si alguien lo discute o si una inspección lo pregunta.
--
-- El consentimiento comercial va en su propia columna porque es un consentimiento distinto: debe poder
-- darse, negarse y retirarse sin tocar el alta (art. 7 RGPD, libre y específico). La columna existente
-- marketing_opt_out se mantiene para no romper lo que ya la lee, pero el opt-in explícito manda.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS terms_accepted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS terms_accepted_version VARCHAR(20),
    ADD COLUMN IF NOT EXISTS marketing_opt_in_at TIMESTAMPTZ;

COMMENT ON COLUMN users.terms_accepted_at IS
    'Cuándo aceptó el usuario los términos y la política de privacidad.';
COMMENT ON COLUMN users.terms_accepted_version IS
    'Versión (fecha) de los textos legales que aceptó, para saber QUÉ aceptó y no sólo que aceptó algo.';
COMMENT ON COLUMN users.marketing_opt_in_at IS
    'Cuándo consintió recibir comunicaciones comerciales. NULL = nunca lo consintió.';

-- Las cuentas anteriores a este cambio no tienen constancia y no se la inventamos: quedan a NULL. Se
-- les pedirá la aceptación la próxima vez que sea necesario, que es lo honesto.
