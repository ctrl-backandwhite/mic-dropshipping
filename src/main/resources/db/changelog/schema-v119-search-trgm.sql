--liquibase formatted sql

--changeset nexa:v119-search-extensions
-- Búsqueda multilingüe: unaccent (folding de acentos) + pg_trgm (trigramas → LIKE con índice, similitud de
-- palabra para plurales/erratas, y segmentación por trigramas para chino sin espacios). Son extensiones
-- "trusted": el owner de la BD puede crearlas sin superusuario.
CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

--changeset nexa:v119-search-nx-norm
-- Normalizador INMUTABLE (minúsculas + sin acentos) para poder indexarlo. unaccent en su forma de 2
-- argumentos (con el diccionario) es inmutable; se envuelve para índices de expresión y para usarlo en la
-- query. Deja el chino intacto (unaccent no toca CJK).
CREATE OR REPLACE FUNCTION nx_norm(text) RETURNS text AS $$
  SELECT lower(unaccent('unaccent', coalesce($1, '')))
$$ LANGUAGE sql IMMUTABLE;

--changeset nexa:v119-search-trgm-indexes
-- Índices GIN de trigramas sobre el texto NORMALIZADO. Aceleran tanto el LIKE '%x%' (comodín inicial) como
-- la similitud de palabra (%>/<%), en todos los idiomas. Uno por campo buscable.
CREATE INDEX IF NOT EXISTS ix_trgm_product_title_zh ON product USING gin (nx_norm(title_zh) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS ix_trgm_product_slug     ON product USING gin (nx_norm(slug) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS ix_trgm_product_external ON product USING gin (nx_norm(external_id) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS ix_trgm_ptrans_title ON product_translation USING gin (nx_norm(title) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS ix_trgm_ptrans_short ON product_translation USING gin (nx_norm(short_description) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS ix_trgm_ptrans_desc  ON product_translation USING gin (nx_norm(description) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS ix_trgm_pattr_value  ON product_attribute USING gin (nx_norm(attr_value) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS ix_trgm_vv_value_zh  ON variant_value USING gin (nx_norm(value_zh) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS ix_trgm_vv_value     ON variant_value USING gin (nx_norm(value) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS ix_trgm_vvt_value    ON variant_value_translation USING gin (nx_norm(value) gin_trgm_ops);

--changeset nexa:v119-search-wsim-threshold
-- Umbral de similitud de PALABRA para los operadores <%/%> (plurales, erratas, multi-término). 0.45 separa
-- limpio lo relevante (multi-término ~0.5-0.6) del ruido (<0.4). Se fija a nivel de BD para todas las
-- conexiones nuevas. El operador (a diferencia de la función word_similarity) SÍ usa el índice GIN → rápido.
ALTER DATABASE nexadrop SET pg_trgm.word_similarity_threshold = 0.45;
