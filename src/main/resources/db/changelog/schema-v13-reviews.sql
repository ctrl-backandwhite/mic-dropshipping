--liquibase formatted sql
--changeset nexadrop:v13-reviews
-- DROP-445: tabla de reseñas de producto. Estructura mínima viable con rating 1-5,
-- comentario, autor opcional (anónimo permitido), país, tags y aprobación admin.

CREATE TABLE IF NOT EXISTS product_review (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES product(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    author_name VARCHAR(120),
    author_country VARCHAR(8),
    rating SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    title VARCHAR(160),
    body TEXT,
    tags VARCHAR(500),
    helpful_count INT NOT NULL DEFAULT 0,
    verified_purchase BOOLEAN NOT NULL DEFAULT FALSE,
    approved BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_review_product ON product_review(product_id, approved, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_review_user    ON product_review(user_id);
CREATE INDEX IF NOT EXISTS idx_review_rating  ON product_review(product_id, rating);

-- Seed 3-5 reseñas por producto demo. Se genera al boot via DemoOperationsSeedRunner.
COMMENT ON TABLE product_review IS 'Reseñas de productos. Filtrable por rating, tags, país.';
