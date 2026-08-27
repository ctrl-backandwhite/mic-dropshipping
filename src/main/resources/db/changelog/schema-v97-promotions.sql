--liquibase formatted sql

--changeset nexadrop:v97-promotions splitStatements:false
-- Rebajas y promociones.
--
-- Hasta ahora el precio de escaparate salía de un cálculo fijo (coste → margen → divisa) y lo único
-- que rebajaba algo era el código de referido, ya en el checkout. No había forma de hacer una rebaja
-- de temporada ni de enseñar el precio anterior tachado, que es lo que convierte una rebaja en una
-- rebaja a ojos del cliente.
--
-- Una promoción es una regla con vigencia: cuánto descuenta, sobre qué y entre qué fechas. Sirve para
-- las tres cosas que hacen falta, cambiando solo `kind` y si lleva `code`:
--   - rebaja automática (invierno, verano, liquidación): sin código, se aplica sola
--   - cupón: con código, el cliente lo teclea en el checkout
--   - la del referido, que ya existía, para que se muestre igual que las demás
CREATE TABLE IF NOT EXISTS promotion (
    id               uuid PRIMARY KEY,
    name             varchar(160) NOT NULL,
    -- Sin código = rebaja automática, visible en el catálogo. Con código = cupón que hay que teclear.
    code             varchar(40),
    kind             varchar(24)  NOT NULL DEFAULT 'SEASONAL',
    -- ALL (toda la tienda) · CATEGORY · PRODUCT. El detalle vive en promotion_target.
    scope            varchar(16)  NOT NULL DEFAULT 'ALL',

    -- Uno de los dos, nunca ambos: porcentaje o importe fijo.
    percent_off      numeric(5,2),
    amount_off_cents integer,

    starts_at        timestamptz,
    ends_at          timestamptz,
    active           boolean      NOT NULL DEFAULT true,
    -- Con dos promociones aplicables gana la de MAYOR descuento; la prioridad solo desempata.
    priority         integer      NOT NULL DEFAULT 0,

    -- Límites de uso, para cupones.
    max_uses         integer,
    used_count       integer      NOT NULL DEFAULT 0,
    min_order_cents  integer,

    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz,

    -- Un descuento sin cantidad no descuenta nada, y con las dos no se sabe cuál gana.
    CONSTRAINT ck_promotion_amount CHECK (
        (percent_off IS NOT NULL AND amount_off_cents IS NULL)
        OR (percent_off IS NULL AND amount_off_cents IS NOT NULL)),
    -- Un 0% no rebaja y un 100% regala el producto: ambos son errores de configuración, no promociones.
    CONSTRAINT ck_promotion_percent CHECK (percent_off IS NULL OR (percent_off > 0 AND percent_off < 100)),
    CONSTRAINT ck_promotion_amount_positive CHECK (amount_off_cents IS NULL OR amount_off_cents > 0),
    -- Una vigencia al revés no se activa nunca y es dificilísima de detectar mirando la pantalla.
    CONSTRAINT ck_promotion_window CHECK (starts_at IS NULL OR ends_at IS NULL OR ends_at > starts_at)
);

-- El código del cupón identifica: dos promociones no pueden compartirlo o no se sabría cuál aplicar.
-- Se guarda en MAYÚSCULAS para que «verano25» y «VERANO25» sean el mismo cupón.
CREATE UNIQUE INDEX IF NOT EXISTS uq_promotion_code ON promotion (upper(code)) WHERE code IS NOT NULL;
-- El escaparate pregunta constantemente «¿qué promociones están vivas ahora?».
CREATE INDEX IF NOT EXISTS idx_promotion_live ON promotion (active, starts_at, ends_at);

-- Sobre qué se aplica. Vacío cuando el alcance es ALL.
CREATE TABLE IF NOT EXISTS promotion_target (
    id           uuid PRIMARY KEY,
    promotion_id uuid NOT NULL REFERENCES promotion (id) ON DELETE CASCADE,
    category_id  uuid REFERENCES category (id) ON DELETE CASCADE,
    product_id   uuid REFERENCES product (id) ON DELETE CASCADE,
    CONSTRAINT ck_promotion_target_one CHECK (
        (category_id IS NOT NULL AND product_id IS NULL)
        OR (category_id IS NULL AND product_id IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_promotion_target_promo ON promotion_target (promotion_id);
CREATE INDEX IF NOT EXISTS idx_promotion_target_category ON promotion_target (category_id)
    WHERE category_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_promotion_target_product ON promotion_target (product_id)
    WHERE product_id IS NOT NULL;

COMMENT ON TABLE promotion IS
    'Rebajas, cupones y promociones con vigencia. Sin código = automática; con código = cupón.';
COMMENT ON COLUMN promotion.priority IS
    'Solo desempata: entre dos promociones aplicables gana siempre la de mayor descuento.';
COMMENT ON TABLE promotion_target IS
    'Categorías o productos concretos a los que alcanza la promoción. Vacío si el alcance es ALL.';
