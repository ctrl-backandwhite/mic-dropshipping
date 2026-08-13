--liquibase formatted sql

--changeset nexa:v112-outbound-email-attachment
-- Adjunto (PDF) opcional para los correos salientes. Se usa para adjuntar la factura del plan al correo de
-- confirmación de contratación. Los bytes viajan con el correo para que el envío diferido los adjunte.
ALTER TABLE outbound_email ADD COLUMN IF NOT EXISTS attachment_bytes bytea;
ALTER TABLE outbound_email ADD COLUMN IF NOT EXISTS attachment_filename varchar(200);
