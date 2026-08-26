--liquibase formatted sql

--changeset nexadrop:v155-mirror-attempts
--comment Contador de intentos de espejado, para reintentar las imagenes FAILED con espera creciente

-- El 25-ago-2026 se cargaron 415 productos cuyas 4.835 imagenes quedaron en FAILED por tiempos de espera
-- agotados contra alicdn. Las URLs eran correctas -respondian 200 al pedirlas de una en una-, pero el
-- escaparate oculta los productos sin imagen espejada: esos 415 estaban cargados y no se podian ni ver ni
-- comprar, y la cifra crecia cada hora.
--
-- Lo unico que reencolaba las fallidas era el saneo del arranque, que corre UNA vez por proceso. Se
-- reintentaron todas de golpe al reiniciar y volvieron a fallar por el mismo motivo, porque el problema no
-- era la URL sino el ritmo. Con este contador cada imagen espera cada vez mas entre intento e intento, y
-- las que agotan el tope dejan de consumir el lote que necesitan las recuperables.
ALTER TABLE product_image
  ADD COLUMN IF NOT EXISTS mirror_attempts INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN product_image.mirror_attempts IS
  'Intentos de espejado fallidos consecutivos. Fija la espera antes del siguiente intento (base x 2^intentos) y, al llegar al tope, se deja de reintentar. Vuelve a 0 cuando la imagen se espeja.';

-- Indice para el barrido de reintento: busca FAILED por debajo del tope, de la mas antigua a la mas nueva.
CREATE INDEX IF NOT EXISTS idx_product_image_retry
  ON product_image (mirror_status, mirror_attempts, updated_at)
  WHERE mirror_status = 'FAILED';

--rollback DROP INDEX IF EXISTS idx_product_image_retry; ALTER TABLE product_image DROP COLUMN IF EXISTS mirror_attempts;
