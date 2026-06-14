--liquibase formatted sql

--changeset nexadrop:v53-store-language
-- Registro de idiomas de la tienda, configurable por el operador (ilimitado). Define qué idiomas
-- se ofrecen para cargar contenido y para que el comprador elija. is_default = idioma base de fallback.
CREATE TABLE IF NOT EXISTS store_language (
    id uuid PRIMARY KEY,
    code varchar(8) NOT NULL,
    label varchar(80) NOT NULL,
    flag varchar(16),
    position int NOT NULL DEFAULT 0,
    active boolean NOT NULL DEFAULT true,
    is_default boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz,
    created_by varchar(120),
    updated_by varchar(120),
    CONSTRAINT uq_store_language_code UNIQUE (code)
);

--changeset nexadrop:v53-store-language-seed
-- Idiomas iniciales (los que ya existían en la app). El operador puede añadir/activar más.
INSERT INTO store_language (id, code, label, flag, position, active, is_default) VALUES
 (gen_random_uuid(), 'es', 'Español',    '🇪🇸', 0, true, true),
 (gen_random_uuid(), 'en', 'English',    '🇺🇸', 1, true, false),
 (gen_random_uuid(), 'pt', 'Português',  '🇧🇷', 2, true, false),
 (gen_random_uuid(), 'zh', '中文',        '🇨🇳', 3, true, false),
 (gen_random_uuid(), 'fr', 'Français',   '🇫🇷', 4, true, false),
 (gen_random_uuid(), 'de', 'Deutsch',    '🇩🇪', 5, true, false),
 (gen_random_uuid(), 'it', 'Italiano',   '🇮🇹', 6, true, false),
 (gen_random_uuid(), 'nl', 'Nederlands', '🇳🇱', 7, true, false)
ON CONFLICT (code) DO NOTHING;
