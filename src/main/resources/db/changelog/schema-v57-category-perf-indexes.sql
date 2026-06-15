--liquibase formatted sql

--changeset nexadrop:v57-category-perf-indexes
-- Endpoint paginado/indexado de categorías: el listado se ordena por position (y por parent_id+position
-- al navegar el árbol). Estos índices permiten que PostgreSQL sirva LIMIT/OFFSET ordenado sin escanear ni
-- ordenar toda la tabla. El slug ya tiene índice único implícito.
CREATE INDEX IF NOT EXISTS idx_category_position ON category (position);
CREATE INDEX IF NOT EXISTS idx_category_parent_position ON category (parent_id, position);
