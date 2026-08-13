--liquibase formatted sql

--changeset nexa:v119-search-extensions
-- Búsqueda multilingüe: unaccent (folding de acentos) + pg_trgm (trigramas → LIKE con índice, similitud de
-- palabra para plurales/erratas, y segmentación por trigramas para chino sin espacios). Son extensiones
-- "trusted": el owner de la BD puede crearlas sin superusuario.
CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

--changeset nexa:v119-search-nx-norm runOnChange:true
-- Normalizador INMUTABLE (minúsculas + sin acentos) para poder indexarlo. unaccent en su forma de 2
-- argumentos (con el diccionario) es inmutable; se envuelve para índices de expresión y para usarlo en la
-- query. Deja el chino intacto (unaccent no toca CJK).
--
-- El esquema va CUALIFICADO a propósito, y no es cosmético: desde PostgreSQL 17 las operaciones de
-- MANTENIMIENTO (CREATE INDEX entre ellas) se ejecutan con un search_path seguro y restringido. Como
-- unaccent vive en "public", al hacer inlining de esta función durante el CREATE INDEX dejaba de
-- resolverse y Liquibase moría con «function unaccent(unknown, text) does not exist» — es decir, la
-- aplicación NO ARRANCABA. No se veía en local (postgres:16) y sí en el despliegue (PostgreSQL 18).
--
-- runOnChange porque este changeset ya está registrado como ejecutado en los entornos donde falló el
-- de los índices: sin él, la función antigua seguiría en su sitio y el arreglo no llegaría nunca.
-- CREATE OR REPLACE es idempotente y el resultado de la función no cambia, sólo cómo resuelve el nombre.
CREATE OR REPLACE FUNCTION public.nx_norm(text) RETURNS text AS $$
  SELECT lower(public.unaccent('public.unaccent'::regdictionary, coalesce($1, '')))
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
--validCheckSum: ANY
-- Umbral de similitud de PALABRA para los operadores <%/%> (plurales, erratas, multi-término). 0.45 separa
-- limpio lo relevante (multi-término ~0.5-0.6) del ruido (<0.4). Se fija a nivel de BD para todas las
-- conexiones nuevas. El operador (a diferencia de la función word_similarity) SÍ usa el índice GIN → rápido.
--
-- El nombre de la base NO puede ir escrito a mano: sólo coincide en local. En los tests (Testcontainers) la
-- base se llama "test" y en el despliegue puede llamarse de otra forma, así que un "ALTER DATABASE nexadrop"
-- fallaba, y con él fallaba Liquibase entero: la aplicación NO ARRANCABA en ningún entorno cuya base no se
-- llamara igual que la de local. Se resuelve con current_database().
--
-- Y si el usuario de la aplicación no es el dueño de la base (algunos proveedores gestionados), tampoco debe
-- impedir el arranque: se avisa y se sigue. Sin el ajuste, el umbral es el 0.5 por defecto de PostgreSQL —
-- algo más estricto, nunca incorrecto.
DO $$
BEGIN
  EXECUTE format('ALTER DATABASE %I SET pg_trgm.word_similarity_threshold = 0.45', current_database());
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'Sin privilegios para fijar pg_trgm.word_similarity_threshold; se usa el valor por defecto';
END
$$;
