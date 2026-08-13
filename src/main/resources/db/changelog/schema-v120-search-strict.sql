--liquibase formatted sql

--changeset nexa:v120-strict-wsim-threshold
--validCheckSum: ANY
-- Precisión del fuzzy: se pasa al operador de SIMILITUD ESTRICTA de palabra (<<%), que respeta límites de
-- palabra y NO confunde "botas" con "botones". Umbral 0.45 (deja pasar plurales/erratas/multi-término
-- legítimos —todos ≥0.5— y excluye los falsos positivos como botones=0.27).
--
-- Igual que en v119: el nombre de la base se resuelve en tiempo de ejecución (escrito a mano sólo valía para
-- local y tumbaba el arranque en el resto) y la falta de privilegios no puede impedir arrancar.
DO $$
BEGIN
  EXECUTE format('ALTER DATABASE %I SET pg_trgm.strict_word_similarity_threshold = 0.45', current_database());
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'Sin privilegios para fijar pg_trgm.strict_word_similarity_threshold; se usa el valor por defecto';
END
$$;
