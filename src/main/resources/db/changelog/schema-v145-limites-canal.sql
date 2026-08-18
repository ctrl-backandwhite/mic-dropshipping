--liquibase formatted sql

--changeset nexadrop:v145-limites-canal splitStatements:false
--
-- El peso máximo por bulto no es un número, es una tabla.
--
-- Hasta hoy el reparto de un pedido en bultos usaba un ESCALAR global
-- (`nexadrop.yunexpress.max-parcel-weight-grams`) y el peso volumétrico se aplicaba SIEMPRE con divisor
-- 6000 (`volumetric-divisor`). Los dos son falsos, y se puede comprobar en la cotización oficial
-- `云途整合渠道-2026-8-17.xlsx` de la cuenta CNHC459832:
--
--   * `FZZXR` (línea de ropa, hoja 云途全球服装专线挂号, sección 六、重量要求): «其他国家：0<W≤30KG» y una
--     lista de excepciones por país — 20 kg a Reino Unido, Países Bajos, Bélgica, Suecia, Irlanda y
--     Australia; 15 kg a Dinamarca y Jordania; 10 kg a Japón, Emiratos y Arabia Saudí; 5 kg a Noruega e
--     Israel. Y no aplica peso volumétrico en NINGÚN país: «所有国家：包裹实际重量不计材积».
--   * `THPHR` (carga general, hoja 云途全球专线挂号（特惠普货）, misma sección): mismo tope general, más
--     excepciones —hasta 2 kg en doce destinos— y sí aplica volumétrico, pero dividiendo entre 8000:
--     «体积重量计算方式为:长*宽*高cm/8000=KG».
--
-- Lo que costaba no tenerlo: cotizar y repartir por un límite que no es el del envío que se está
-- haciendo. Si el escalar queda por encima del tope real —30 kg configurados contra los 15 kg que admite
-- Dinamarca— el transportista rechaza la guía con el pedido ya cobrado, o repesa en almacén y factura la
-- diferencia contra el margen. Si queda por debajo, se parten pedidos que cabían enteros y cada bulto de
-- más es una guía de más que se paga completa. Con el volumétrico pasa lo mismo al revés: aplicarlo en la
-- línea de ropa, que factura el peso real, encarece el envío por un dato que dice justo lo contrario.
--
-- El país `*` es el valor por defecto del canal. La resolución va de lo particular a lo general —fila
-- exacta, fila comodín del canal y, si el canal no está aquí, la configuración global de siempre—: ese
-- último escalón se conserva a propósito porque el entorno de pruebas cotiza por el canal `BPA`, que no
-- existe en producción y por eso no se siembra.

CREATE TABLE IF NOT EXISTS carrier_channel_limit (
    id                  UUID PRIMARY KEY,
    channel_code        VARCHAR(32)  NOT NULL,
    country_code        VARCHAR(2)   NOT NULL,
    max_weight_grams    INT          NOT NULL DEFAULT 0,
    volumetric_divisor  INT          NOT NULL DEFAULT 0,
    min_billable_grams  INT          NOT NULL DEFAULT 0,
    max_length_mm       INT          NOT NULL DEFAULT 0,
    max_width_mm        INT          NOT NULL DEFAULT 0,
    max_height_mm       INT          NOT NULL DEFAULT 0,
    single_parcel_only  BOOLEAN      NOT NULL DEFAULT TRUE,
    notes               VARCHAR(200),
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(64),
    updated_by          VARCHAR(64)
);

-- Uno y solo un límite por (canal, país): dos filas para el mismo par dejarían el reparto a merced del
-- orden con que la base devuelva las filas, es decir, a merced de nada.
CREATE UNIQUE INDEX IF NOT EXISTS ux_carrier_channel_limit_canal_pais
    ON carrier_channel_limit (channel_code, country_code);

COMMENT ON TABLE carrier_channel_limit IS
    'Peso máximo por bulto, divisor volumétrico y medidas de cada canal del transportista en cada país.';

-- FZZXR — línea de ropa. NO aplica peso volumétrico (divisor 0) y va un bulto por envío.
INSERT INTO carrier_channel_limit (id, channel_code, country_code, max_weight_grams, volumetric_divisor,
                                   min_billable_grams, max_length_mm, max_width_mm, max_height_mm,
                                   single_parcel_only, notes, active, created_by, updated_by)
SELECT gen_random_uuid(), 'FZZXR', d.pais, d.peso, 0, d.minimo, 600, 400, 350, TRUE,
       '云途全球服装专线挂号 六、重量要求', TRUE, 'v145-limites-canal', 'v145-limites-canal'
  FROM (VALUES
  ('*',  30000,   0),
  ('GB', 20000,   0),
  ('NL', 20000,   0),
  ('BE', 20000,   0),
  ('SE', 20000,   0),
  ('IE', 20000,   0),
  ('AU', 20000,   0),
  ('NZ', 25000,   0),
  ('DK', 15000,   0),
  ('JO', 15000, 100),
  ('JP', 10000,   0),
  ('AE', 10000, 100),
  ('SA', 10000, 100),
  ('NO',  5000,   0),
  ('IL',  5000,   0),
  ('US', 30000,  30),
  ('CA', 30000,  50),
  ('SG', 30000, 100)
  ) AS d(pais, peso, minimo)
 WHERE NOT EXISTS (
     SELECT 1 FROM carrier_channel_limit e
      WHERE e.channel_code = 'FZZXR' AND e.country_code = d.pais);

-- THPHR — carga general. Sí aplica volumétrico, con SU divisor (8000, no 6000).
INSERT INTO carrier_channel_limit (id, channel_code, country_code, max_weight_grams, volumetric_divisor,
                                   min_billable_grams, max_length_mm, max_width_mm, max_height_mm,
                                   single_parcel_only, notes, active, created_by, updated_by)
SELECT gen_random_uuid(), 'THPHR', d.pais, d.peso, 8000, d.minimo, 600, 400, 350, TRUE,
       '云途全球专线挂号（特惠普货）六、重量要求', TRUE, 'v145-limites-canal', 'v145-limites-canal'
  FROM (VALUES
  ('*',  30000,   0),
  ('GB', 20000,   0),
  ('NL', 20000,   0),
  ('BE', 20000,   0),
  ('SE', 20000,   0),
  ('IE', 20000,   0),
  ('AU', 20000,   0),
  ('KR', 20000,   0),
  ('BR', 20000,   0),
  ('TH', 25000,   0),
  ('NZ', 25000,   0),
  -- Dinamarca no figura en la lista de excepciones, pero su tabla de tarifas se corta en 15 kg: cotizar
  -- 30 kg por un canal que no tarifa por encima de 15 es prometer un envío sin precio.
  ('DK', 15000,   0),
  ('JO', 15000,   0),
  ('ZA', 10000, 100),
  ('AE', 10000,   0),
  ('SA', 10000,   0),
  ('PH', 10000,   0),
  ('MX', 10000,  20),
  ('JP', 10000,   0),
  ('IL',  5000,   0),
  ('CH',  5000,   0),
  ('NO',  5000,   0),
  ('MA',  5000,   0),
  ('TZ',  2000,   0),
  ('RW',  2000,   0),
  ('AO',  2000,   0),
  ('AZ',  2000,   0),
  ('SN',  2000,   0),
  ('MU',  2000,   0),
  ('RE',  2000,   0),
  ('MG',  2000,   0),
  ('SC',  2000,   0),
  ('ZM',  2000,   0),
  ('PK',  2000,  50),
  ('PE',  2000,   0),
  ('US', 30000,  30)
  ) AS d(pais, peso, minimo)
 WHERE NOT EXISTS (
     SELECT 1 FROM carrier_channel_limit e
      WHERE e.channel_code = 'THPHR' AND e.country_code = d.pais);
