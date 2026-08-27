--liquibase formatted sql

--changeset nexadrop:v95-newsletter-double-optin splitStatements:false
-- Doble opt-in en la newsletter.
--
-- Bastaba con escribir una dirección en el formulario del pie para quedar suscrito, así que cualquiera
-- podía dar de alta el correo de otra persona; y ante una reclamación no había forma de demostrar que
-- el titular hubiera consentido nada. Ahora el alta nace PENDING y sólo pasa a SUBSCRIBED cuando
-- alguien pulsa el enlace que llega a ese buzón: ese clic es la prueba de que quien consiente tiene
-- acceso al correo.

ALTER TABLE newsletter_subscriber
    ADD COLUMN IF NOT EXISTS confirmed_at TIMESTAMPTZ;

COMMENT ON COLUMN newsletter_subscriber.confirmed_at IS
    'Cuándo se confirmó el alta desde el propio buzón. NULL en las altas pendientes y en las antiguas.';

-- Las altas que ya existían se dejan como están: se recogieron con las reglas de entonces y borrarlas o
-- degradarlas no mejora a nadie. Lo que no se hace es fingir que fueron confirmadas — quedan a NULL, y
-- así se distinguen de las que sí pasaron por el doble opt-in.
