--liquibase formatted sql

--changeset nexadrop:v68-variant-image-mirror-failed
-- Marca de fallo de espejado para imágenes de VARIANTE y de VALOR de eje. Igual que el estado FAILED
-- de product_image: una vez intentada sin éxito (p.ej. origen alicdn muerto/404), se marca aquí para
-- NO reintentarla en bucle en cada ciclo del job. La capa de vista sigue cayendo a image_source_url.
ALTER TABLE product_variant ADD COLUMN IF NOT EXISTS image_mirror_failed_at TIMESTAMPTZ;
ALTER TABLE variant_value  ADD COLUMN IF NOT EXISTS image_mirror_failed_at TIMESTAMPTZ;
