--liquibase formatted sql

--changeset nexadrop:v169-dispositivos-para-avisos
--comment El buzon de avisos existia solo DENTRO de la aplicacion: habia que abrirla para enterarse de que
--un pedido habia salido. Para mandar un aviso al sistema operativo hace falta guardar a que dispositivos
--mandarselo, que es lo que faltaba y lo que tenia el push bloqueado.
--
--Una persona puede tener varios dispositivos y un dispositivo puede cambiar de manos: por eso la clave
--unica es el TOKEN, no el usuario. Si el mismo token vuelve con otro usuario, la fila se reasigna en vez
--de duplicarse; asi el telefono de quien vendio su movil deja de recibir los avisos del dueno anterior.
--
--`ultima_senal` sirve para retirar los que ya no vuelven: un token de Expo caduca cuando la aplicacion se
--desinstala, y seguir mandandole avisos gasta cuota y ensucia el registro de errores.

CREATE TABLE IF NOT EXISTS user_device (
    id             UUID PRIMARY KEY,
    user_id        UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    push_token     VARCHAR(255) NOT NULL,
    plataforma     VARCHAR(16)  NOT NULL DEFAULT 'UNKNOWN',
    creado_el      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    ultima_senal   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_user_device_push_token UNIQUE (push_token)
);

CREATE INDEX IF NOT EXISTS idx_user_device_user ON user_device (user_id);
