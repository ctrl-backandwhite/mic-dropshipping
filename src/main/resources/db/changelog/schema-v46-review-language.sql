--liquibase formatted sql

--changeset nexadrop:v46-review-language
-- Idioma de la reseña, para poder crear/mostrar reseñas por idioma (es/en/pt/zh).
ALTER TABLE product_review ADD COLUMN IF NOT EXISTS language varchar(8);
CREATE INDEX IF NOT EXISTS idx_review_product_lang ON product_review(product_id, language);
