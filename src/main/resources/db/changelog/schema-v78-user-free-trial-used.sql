--liquibase formatted sql

--changeset nexadrop:v78-user-free-trial-used
-- El plan GRATIS es una PRUEBA de 1 mes y solo puede usarse UNA vez por cuenta/correo. Marcamos con esta
-- bandera al usuario en cuanto contrata el plan gratis, para rechazar futuras contrataciones del plan de
-- prueba (debe pasar a un plan de pago). El email es único por cuenta, así que la bandera por usuario
-- cubre el caso "mismo correo".
ALTER TABLE users ADD COLUMN IF NOT EXISTS free_trial_used BOOLEAN NOT NULL DEFAULT false;
