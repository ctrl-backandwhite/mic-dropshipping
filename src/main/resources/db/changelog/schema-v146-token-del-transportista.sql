--liquibase formatted sql

--changeset nexadrop:v146-token-del-transportista splitStatements:false
--
-- Dónde vive el token de la API de un transportista.
--
-- CJ Dropshipping limita la autenticación a UNA llamada por segundo y devuelve el mismo token si se lo
-- vuelves a pedir dentro de 24 horas. Guardarlo solo en memoria significa que cada despliegue —y cada
-- réplica -- pide uno nuevo: con un solo contenedor pasa desapercibido, con dos es una carrera contra el
-- límite justo cuando alguien está pagando. Por eso vive en la base y lo comparten todas las instancias.
--
-- `access_token` va en TEXT y no en VARCHAR(255) a propósito: el token real que devolvió CJ el
-- 18-ago-2026 ocupa 566 caracteres. Con VARCHAR(255) la fila se rechaza en producción y el fallo aparece
-- a los diez días, cuando toque renovar, no al desplegar.
--
-- La tabla es genérica (`carrier`) y no «cj_token» porque YunExpress ya usa OAuth2 con su propio token
-- en memoria: el día que se mueva aquí, no hace falta otra tabla.
CREATE TABLE IF NOT EXISTS carrier_token (
    id                  UUID PRIMARY KEY,
    carrier             VARCHAR(32)  NOT NULL,
    access_token        TEXT         NOT NULL,
    refresh_token       TEXT,
    open_id             VARCHAR(64),
    -- Cuándo se obtuvo: es lo que decide la renovación, y se mide contra ESTO y no contra la caducidad
    -- que anuncia el transportista, porque renovamos mucho antes de que caduque.
    obtained_at         TIMESTAMPTZ  NOT NULL,
    access_expires_at   TIMESTAMPTZ,
    refresh_expires_at  TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          VARCHAR(64),
    updated_by          VARCHAR(64)
);

-- Un solo token vivo por transportista: si hubiera dos filas, media plataforma usaría uno y media otro.
CREATE UNIQUE INDEX IF NOT EXISTS ux_carrier_token_carrier ON carrier_token (carrier);
