-- Registra los valores de eje que faltaban, con sus siete traducciones.
--
-- Los detecta `variantes-sin-traduccion.sql`. Son restos de cargas antiguas: el valor viajaba en
-- `options_json` de la variante pero nunca llegó a darse de alta como valor del eje, así que no había
-- nada que traducir y el comprador leía el texto del proveedor tal cual.
--
-- Se puede pasar por los tres entornos sin llevar cuenta de dónde ya se aplicó: el alta va guardada
-- tras un `NOT EXISTS` sobre la pareja (eje, valor), así que una segunda pasada no inserta nada y las
-- traducciones cuelgan del `RETURNING`, que en ese caso viene vacío.
--
-- OJO con el único caso que NO repara: si el valor del eje existe pero le faltan traducciones, esto no
-- las añade —no llega a insertar el valor, así que no hay `RETURNING` del que colgarlas—. Ese caso hay
-- que verlo con la consulta hermana y tratarlo aparte.
--
-- 918794653705 · Talla «L【105-130斤】»: 斤 es medio kilo, así que 105-130 斤 son 52-65 kg.
-- 630557257264 · Color «Lexus-咖色»: el eje ya tenía «咖色» suelto; la variante lo lleva con marca
-- delante y por eso no casaban. Se da de alta el valor tal y como viene, con la marca respetada.
BEGIN;

CREATE TEMP TABLE valor_a_reparar (
    external_id  varchar(120),
    eje_zh       varchar(120),
    valor_zh     varchar(200),
    es varchar(200), en varchar(200), pt varchar(200),
    fr varchar(200), de varchar(200), it varchar(200), nl varchar(200)
) ON COMMIT DROP;

INSERT INTO valor_a_reparar VALUES
  ('918794653705', 'Talla', 'L【105-130斤】',
   'L (52-65 kg)', 'L (52-65 kg)', 'L (52-65 kg)',
   'L (52-65 kg)', 'L (52-65 kg)', 'L (52-65 kg)', 'L (52-65 kg)'),
  ('630557257264', 'Color', 'Lexus-咖色',
   'Lexus · Café', 'Lexus · Coffee', 'Lexus · Café',
   'Lexus · Café', 'Lexus · Mokkabraun', 'Lexus · Caffè', 'Lexus · Bruin');

WITH nuevo AS (
    INSERT INTO variant_value (id, option_id, value_zh, position, created_at, updated_at, created_by)
    SELECT gen_random_uuid(), vo.id, r.valor_zh,
           COALESCE((SELECT max(vv.position) + 1 FROM variant_value vv WHERE vv.option_id = vo.id), 0),
           now(), now(), 'repara-variantes'
    FROM valor_a_reparar r
    JOIN product p ON p.external_id = r.external_id
    JOIN variant_option vo ON vo.product_id = p.id AND vo.name_zh = r.eje_zh
    WHERE NOT EXISTS (
        SELECT 1 FROM variant_value vv WHERE vv.option_id = vo.id AND vv.value_zh = r.valor_zh)
    RETURNING id, option_id, value_zh
)
INSERT INTO variant_value_translation (id, variant_value_id, language, value, created_at, updated_at, created_by)
SELECT gen_random_uuid(), nuevo.id, idioma.lang, idioma.texto, now(), now(), 'repara-variantes'
FROM nuevo
JOIN valor_a_reparar r ON r.valor_zh = nuevo.value_zh
CROSS JOIN LATERAL (VALUES ('es', r.es), ('en', r.en), ('pt', r.pt),
                           ('fr', r.fr), ('de', r.de), ('it', r.it), ('nl', r.nl)) AS idioma(lang, texto);

COMMIT;
