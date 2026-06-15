--liquibase formatted sql

--changeset nexadrop:v55-shop-sync-observability
-- DROP-693/DROP-701: observabilidad real del sync de tiendas. Antes una tienda Shopify podía
-- quedar con 0 productos publicados sin razón ni log. Ahora la conexión guarda el resultado del
-- último sync (mensaje + error) y cada listing guarda su error de publicación, surfaced en el panel.
ALTER TABLE user_shop_connection ADD COLUMN IF NOT EXISTS last_sync_error varchar(1000);
ALTER TABLE user_shop_connection ADD COLUMN IF NOT EXISTS last_sync_message varchar(500);
ALTER TABLE shop_product_listing ADD COLUMN IF NOT EXISTS error_message varchar(1000);
