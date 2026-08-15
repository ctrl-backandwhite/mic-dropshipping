--liquibase formatted sql

--changeset nexadrop:v131-aviso-cambio-legal splitStatements:false
--
-- Registro de qué versión de los textos legales se ha avisado ya.
--
-- Cuando cambian los términos o la política de privacidad hay que informar a quien tiene cuenta: el usuario
-- está vinculado por un texto que aceptó, y cambiarlo sin decírselo lo deja obligado por una redacción que
-- nunca vio. El aviso se dispara solo al desplegar una versión nueva, y esta tabla es lo que impide que se
-- repita: sin ella, cada reinicio del servicio volvería a mandar el mismo correo a toda la base de usuarios.
--
-- La clave es la versión, no la fecha: si se despliega dos veces la misma, el INSERT choca y no se envía
-- nada. Y si hubiera que reenviar a propósito, se borra la fila de esa versión.
--
CREATE TABLE IF NOT EXISTS legal_version_notice (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    version         varchar(20)  NOT NULL UNIQUE,
    notified_at     timestamptz  NOT NULL DEFAULT now(),
    recipients      integer      NOT NULL DEFAULT 0,
    created_at      timestamptz  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  legal_version_notice IS
    'Versiones de los textos legales cuyo aviso ya se ha enviado. Evita reenviar en cada arranque.';
COMMENT ON COLUMN legal_version_notice.recipients IS
    'A cuántas cuentas se encoló el aviso. Queda como constancia de haber informado.';

-- La versión que hay publicada AHORA se marca como ya avisada, sin enviar nada. Si no, el primer despliegue
-- con este cambio mandaría un correo de «hemos actualizado las condiciones» a toda la base de usuarios por
-- un texto que no ha cambiado — el aviso perdería credibilidad justo el día que se estrena.
INSERT INTO legal_version_notice (version, notified_at, recipients)
VALUES ('2026-07-31', now(), 0)
ON CONFLICT (version) DO NOTHING;
