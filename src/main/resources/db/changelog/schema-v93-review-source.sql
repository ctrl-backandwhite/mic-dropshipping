--liquibase formatted sql

--changeset nexadrop:v93-review-source splitStatements:false
-- De dónde sale cada reseña, y quitar el "compra verificada" a las que no lo son.
--
-- El catálogo se cargó desde 1688 arrastrando sus reseñas: 77.694 filas, ninguna con user_id, y 77.390
-- marcadas verified_purchase = true. Es decir, se servían al comprador como COMPRA VERIFICADA reseñas
-- que no proceden de ninguna compra en esta tienda; los autores lo delatan («Cliente anónimo», «l**7»,
-- que es cómo 1688 censura los nombres de sus usuarios).
--
-- Presentar una reseña como de un consumidor que compró el producto sin haber tomado medidas
-- razonables para comprobarlo está en la LISTA NEGRA de prácticas comerciales desleales que introdujo
-- la Directiva Omnibus (UE) 2019/2161, transpuesta en España por el RD-ley 24/2021. Estar en la lista
-- negra significa que se considera desleal EN TODO CASO: no hay que demostrar que alguien resultó
-- engañado. Se sanciona tal cual.
--
-- Las reseñas se mantienen visibles —son información útil sobre el producto— pero declarando su origen
-- y sin el distintivo que no les corresponde. El distintivo queda reservado a lo que de verdad puede
-- verificarse: una reseña escrita por el usuario de un pedido entregado.

ALTER TABLE product_review
    ADD COLUMN IF NOT EXISTS source VARCHAR(16) NOT NULL DEFAULT 'SUPPLIER';

COMMENT ON COLUMN product_review.source IS
    'Origen de la reseña: SUPPLIER (importada del catálogo del proveedor) o CUSTOMER (escrita por un comprador de la plataforma).';

-- Una reseña con user_id la escribió alguien de esta plataforma; el resto vino en la carga del catálogo.
UPDATE product_review SET source = 'CUSTOMER' WHERE user_id IS NOT NULL;
UPDATE product_review SET source = 'SUPPLIER' WHERE user_id IS NULL;

-- El distintivo de compra verificada solo puede sobrevivir donde hay un comprador detrás.
UPDATE product_review SET verified_purchase = FALSE WHERE user_id IS NULL AND verified_purchase = TRUE;

CREATE INDEX IF NOT EXISTS idx_product_review_source ON product_review (source);

-- Red de seguridad: que no se pueda volver a marcar como compra verificada algo sin comprador.
ALTER TABLE product_review DROP CONSTRAINT IF EXISTS chk_review_verified_needs_user;
ALTER TABLE product_review
    ADD CONSTRAINT chk_review_verified_needs_user
    CHECK (verified_purchase = FALSE OR user_id IS NOT NULL);
