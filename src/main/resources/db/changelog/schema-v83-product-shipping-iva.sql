-- v83: envío (flete) e IVA como propiedades explícitas del producto, en CNY (como base_price).
-- El precio total mostrado = base×margen + iva + envío. El admin ve el desglose; el usuario, solo el total.
ALTER TABLE product ADD COLUMN IF NOT EXISTS shipping_cny NUMERIC(12,4) DEFAULT 0;
ALTER TABLE product ADD COLUMN IF NOT EXISTS iva_cny NUMERIC(12,4) DEFAULT 0;
