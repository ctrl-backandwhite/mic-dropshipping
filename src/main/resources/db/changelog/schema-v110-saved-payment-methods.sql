--liquibase formatted sql

--changeset nexadrop:v110-saved-payment-methods splitStatements:false
-- Métodos de pago guardados unificados (tarjeta + PayPal) con "predeterminado" único.
--
-- Las TARJETAS siguen viviendo en Stripe (customer + payment methods): no se migran aquí para no tocar
-- el flujo de suscripción que ya funciona. Lo que se añade es:
--   1) payment_method_paypal: cuentas PayPal que el usuario guarda para pagar en el checkout (one-off;
--      NO renovación automática — los planes recurrentes se cobran solo con tarjeta).
--   2) users.default_payment_ref: puntero al método PREDETERMINADO del usuario, unificado sobre tarjetas
--      y PayPal. Guarda el id de Stripe de la tarjeta (pm_...) o 'paypal:<uuid>' para una cuenta PayPal.
--      NULL = sin preferencia explícita; si el usuario tiene UN SOLO método, ese es el predeterminado de
--      facto (lo resuelve la aplicación, no hace falta escribirlo).
CREATE TABLE IF NOT EXISTS payment_method_paypal (
    id               uuid PRIMARY KEY,
    user_id          uuid NOT NULL REFERENCES users (id),
    -- El correo de PayPal es dato personal sensible: se guarda CIFRADO (AES-256-GCM, TokenCryptoService),
    -- igual que el secreto TOTP. Solo se descifra en memoria para mostrar el enmascarado (p***@dominio).
    paypal_email_enc text,
    -- Token de vault de PayPal si en el futuro se guarda para cobrar sin re-aprobar; hoy NULL (one-off).
    vault_id         text,
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_payment_method_paypal_user ON payment_method_paypal (user_id);

-- Método PREDETERMINADO del usuario, unificado sobre tarjetas (Stripe) y PayPal. En tabla aparte para no
-- tocar la entidad users. 'ref' guarda el pm_ de Stripe (tarjeta) o 'paypal:<uuid>'. Sin fila = sin
-- preferencia explícita; si el usuario tiene UN SOLO método, la app lo trata como predeterminado.
CREATE TABLE IF NOT EXISTS user_default_payment (
    user_id    uuid PRIMARY KEY REFERENCES users (id),
    ref        text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
