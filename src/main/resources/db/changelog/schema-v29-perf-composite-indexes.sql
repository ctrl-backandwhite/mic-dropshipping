--liquibase formatted sql
--changeset nexadrop:v29-perf-composite-indexes splitStatements:true runInTransaction:false endDelimiter:;
-- Plan estratégico 300k req/min — Fase 1:
-- Índices compuestos para que el listado del storefront / search / dashboard
-- responda en <10 ms incluso a 5000 req/s. CONCURRENTLY para no bloquear el
-- catálogo en hot reload. runInTransaction:false porque CREATE INDEX
-- CONCURRENTLY no puede ir dentro de transacción. splitStatements:true para
-- que cada CREATE INDEX se envíe como statement aparte — Postgres rechaza
-- `CREATE INDEX CONCURRENTLY` cuando llega dentro de un pipeline multi-statement.

-- Listado por trending por categoría (la query más golpeada)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_product_status_cat_trend
  ON product(status, category_id, trend_score DESC NULLS LAST);

-- Filtro por proveedor (activos)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_product_status_supplier
  ON product(status, supplier_id) WHERE status = 'ACTIVE';

-- Filtro por precio (ordenación price_asc / price_desc + minPrice/maxPrice)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_product_status_price
  ON product(status, base_price);

-- Filtro shipFrom + freeShipping
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_product_status_shipfrom
  ON product(status, ship_from) WHERE status = 'ACTIVE';

-- Sort por monthly_sales (orden "sales")
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_product_status_sales
  ON product(status, monthly_sales DESC NULLS LAST);

-- Sort por rating (orden "rating")
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_product_status_rating
  ON product(status, rating DESC NULLS LAST);

-- Sort por createdAt (orden "newest")
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_product_status_created
  ON product(status, created_at DESC);

-- Búsqueda fuzzy por título (pg_trgm). Si la extensión no está, hace fallback
-- a un GIN simple sobre to_tsvector.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_product_title_trgm
  ON product USING GIN (LOWER(title_zh) gin_trgm_ops);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_product_translation_title_trgm
  ON product_translation USING GIN (LOWER(title) gin_trgm_ops);

-- Órdenes por usuario (PDP de "Mis pedidos")
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_order_user_placed
  ON customer_order(user_id, placed_at DESC);

-- Wallet movimientos por wallet + kind + fecha
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_wallet_tx_wallet_kind_created
  ON wallet_transaction(wallet_id, kind, created_at DESC);

-- Notificaciones por usuario (badge + listado)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_notification_user_read_created
  ON notification(user_id, read_at, created_at DESC);

-- Listings shop (count productos publicados por shop)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_shop_listing_shop
  ON shop_product_listing(user_shop_connection_id, status);
