--liquibase formatted sql

--changeset nexadrop:v47-variant-shipping-dims
-- DROP-675: peso y dimensiones reales POR VARIANTE para el cálculo de envío.
-- weight_grams ya existe; se añaden el peso del paquete y las dimensiones por variante.
ALTER TABLE product_variant ADD COLUMN IF NOT EXISTS package_weight_grams integer;
ALTER TABLE product_variant ADD COLUMN IF NOT EXISTS length_mm integer;
ALTER TABLE product_variant ADD COLUMN IF NOT EXISTS width_mm integer;
ALTER TABLE product_variant ADD COLUMN IF NOT EXISTS height_mm integer;
