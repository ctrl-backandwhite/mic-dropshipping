--liquibase formatted sql

--changeset nexadrop:v88-country-customs-rule splitStatements:false
-- Reglas de despacho aduanero POR PAÍS para el envío DDP (el comercio cobra el impuesto al cliente y el
-- transportista lo liquida en destino, de modo que el comprador no recibe ningún cargo sorpresa).
--
-- Tres cosas que antes no existían y se cobraban/declaraban mal:
--  1) VALOR DECLARADO: se declara el valor INTRÍNSECO (lo que el cliente paga por los bienes), no el coste
--     de compra en 1688. Declarar el coste haría que el transportista repercutiera menos impuesto del que
--     se cobró al cliente (dinero retenido que no corresponde) y es infradeclaración en aduana.
--  2) UMBRAL DE MINIMIS: por encima del umbral de cada país el régimen simplificado (IOSS en la UE, LVG en
--     AU/NZ, VOEC en NO...) deja de aplicar: entran aranceles y despacho formal, y el coste sube.
--  3) HANDLING FEE: el recargo que el transportista cobra por adelantar el impuesto en DDP. Si no está en
--     el precio, se come el margen en cada pedido y solo se descubre al conciliar la factura mensual.
--
-- `de_minimis_amount` va en su DIVISA LEGAL (`de_minimis_currency`): 150 EUR en la UE, 135 GBP en UK,
-- 3000 NOK en Noruega, 1000 AUD en Australia... El servicio lo convierte con la tasa del día si la divisa
-- está en `currency_rate`; si no lo está (NZD/AED/SAR/ILS/TRY), aquí se siembra ya el equivalente en USD.
-- `de_minimis_amount = 0` significa UMBRAL NO CONFIGURADO: no se evalúa y ningún pedido recibe el recargo
-- por despacho formal. Es deliberado — marcar "superado" sin dato encarecería todos los pedidos de ese país
-- sin base real. Cuando el transportista confirme el umbral aplicable, se rellena desde el admin.
--
-- IMPORTANTE: los importes de `handling_fee_cents`, `handling_percent_bps`, `over_threshold_surcharge_cents`
-- y `duty_rate_bps` nacen a 0 a propósito. Son los datos que debe confirmar el transportista (tarifa DDP,
-- recargo por despacho formal). El mecanismo queda cableado: en cuanto YunExpress facilite su tabla, se
-- rellenan desde el admin SIN tocar código. Los umbrales legales también son configurables porque cambian.
CREATE TABLE IF NOT EXISTS country_customs_rule (
  id                             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  country_code                   VARCHAR(2)   NOT NULL UNIQUE,
  -- DDP = impuestos incluidos, los liquida el transportista y los paga el comercio (transparente para el
  -- comprador). DDU = los paga el destinatario en destino (cargo sorpresa: solo para países sin DDP).
  tax_mode                       VARCHAR(3)   NOT NULL DEFAULT 'DDP',
  de_minimis_amount              NUMERIC(12,2) NOT NULL DEFAULT 0,
  de_minimis_currency            VARCHAR(3)   NOT NULL DEFAULT 'EUR',
  -- Qué hacer cuando el valor intrínseco supera el umbral: SURCHARGE (vender aplicando el recargo de
  -- despacho formal), ALLOW (vender sin recargo, asumiendo el sobrecoste) o BLOCK (no permitir el pedido).
  over_threshold_policy          VARCHAR(10)  NOT NULL DEFAULT 'SURCHARGE',
  -- Recargo fijo por paquete que cobra el transportista por gestionar el DDP (céntimos USD).
  handling_fee_cents             INTEGER      NOT NULL DEFAULT 0,
  -- Parte variable del recargo DDP, en puntos básicos sobre el impuesto liquidado (250 = 2,5%).
  handling_percent_bps           INTEGER      NOT NULL DEFAULT 0,
  -- Recargo fijo adicional cuando se supera el umbral (despacho formal, céntimos USD).
  over_threshold_surcharge_cents INTEGER      NOT NULL DEFAULT 0,
  -- Arancel estimado sobre el valor intrínseco cuando se supera el umbral (puntos básicos).
  duty_rate_bps                  INTEGER      NOT NULL DEFAULT 0,
  active                         BOOLEAN      NOT NULL DEFAULT TRUE,
  created_at                     TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at                     TIMESTAMPTZ  NOT NULL DEFAULT now(),
  created_by                     VARCHAR(120),
  updated_by                     VARCHAR(120)
);

CREATE INDEX IF NOT EXISTS idx_country_customs_rule_country ON country_customs_rule (country_code);

--changeset nexadrop:v88-country-customs-rule-seed splitStatements:false
-- Umbrales legales vigentes por país. ON CONFLICT DO NOTHING: nunca pisa lo que el admin haya ajustado.
INSERT INTO country_customs_rule (country_code, tax_mode, de_minimis_amount, de_minimis_currency, over_threshold_policy)
VALUES
 -- === UE-27: régimen IOSS, 150 EUR de valor intrínseco (sin transporte ni seguro) ===
 ('ES','DDP',150,'EUR','SURCHARGE'), ('PT','DDP',150,'EUR','SURCHARGE'), ('FR','DDP',150,'EUR','SURCHARGE'),
 ('DE','DDP',150,'EUR','SURCHARGE'), ('IT','DDP',150,'EUR','SURCHARGE'), ('NL','DDP',150,'EUR','SURCHARGE'),
 ('BE','DDP',150,'EUR','SURCHARGE'), ('IE','DDP',150,'EUR','SURCHARGE'), ('PL','DDP',150,'EUR','SURCHARGE'),
 ('SE','DDP',150,'EUR','SURCHARGE'), ('AT','DDP',150,'EUR','SURCHARGE'), ('DK','DDP',150,'EUR','SURCHARGE'),
 ('FI','DDP',150,'EUR','SURCHARGE'), ('CZ','DDP',150,'EUR','SURCHARGE'), ('GR','DDP',150,'EUR','SURCHARGE'),
 ('HU','DDP',150,'EUR','SURCHARGE'), ('RO','DDP',150,'EUR','SURCHARGE'), ('BG','DDP',150,'EUR','SURCHARGE'),
 ('HR','DDP',150,'EUR','SURCHARGE'), ('SK','DDP',150,'EUR','SURCHARGE'), ('SI','DDP',150,'EUR','SURCHARGE'),
 ('EE','DDP',150,'EUR','SURCHARGE'), ('LV','DDP',150,'EUR','SURCHARGE'), ('LT','DDP',150,'EUR','SURCHARGE'),
 ('LU','DDP',150,'EUR','SURCHARGE'), ('MT','DDP',150,'EUR','SURCHARGE'), ('CY','DDP',150,'EUR','SURCHARGE'),
 -- === Europa no-UE: cada uno con su propio régimen de importación ===
 ('GB','DDP',135,'GBP','SURCHARGE'),   -- UK: 135 GBP; por debajo el IVA lo recauda el vendedor (HMRC)
 ('NO','DDP',3000,'NOK','SURCHARGE'),  -- Noruega: VOEC, 3.000 NOK por artículo
 ('CH','DDP',62,'CHF','SURCHARGE'),    -- Suiza: franquicia por importe de IVA (~62 CHF al tipo normal)
 ('IS','DDP',0,'EUR','SURCHARGE'),     -- Islandia: sin franquicia general → declara siempre
 ('AD','DDP',0,'EUR','SURCHARGE'), ('MC','DDP',150,'EUR','SURCHARGE'), ('LI','DDP',62,'CHF','SURCHARGE'),
 ('SM','DDP',0,'EUR','SURCHARGE'), ('AL','DDP',0,'EUR','SURCHARGE'), ('BA','DDP',0,'EUR','SURCHARGE'),
 ('MK','DDP',0,'EUR','SURCHARGE'), ('ME','DDP',0,'EUR','SURCHARGE'), ('RS','DDP',0,'EUR','SURCHARGE'),
 ('XK','DDP',0,'EUR','SURCHARGE'), ('MD','DDP',0,'EUR','SURCHARGE'), ('UA','DDP',0,'EUR','SURCHARGE'),
 ('TR','DDP',33,'USD','SURCHARGE'),    -- Turquía: ~30 EUR (TRY no está en currency_rate → equivalente USD)
 -- === Norteamérica ===
 -- EE.UU.: la franquicia de 800 USD (Section 321) dejó de aplicar a los envíos procedentes de China. Se
 -- siembra 0 (declara y paga siempre) por ser el criterio conservador; ajustar si el carrier confirma otra cosa.
 ('US','DDP',0,'USD','SURCHARGE'),
 ('CA','DDP',20,'CAD','SURCHARGE'),    -- Canadá: 20 CAD de minimis general (CUSMA no aplica a origen CN)
 ('MX','DDP',50,'USD','SURCHARGE'),    -- México: 50 USD
 -- === Latinoamérica y Caribe ===
 ('BR','DDP',50,'USD','SURCHARGE'),    -- Brasil: Remessa Conforme, 50 USD exento de arancel (ICMS aparte)
 ('AR','DDP',400,'USD','SURCHARGE'), ('CL','DDP',41,'USD','SURCHARGE'), ('CO','DDP',200,'USD','SURCHARGE'),
 ('PE','DDP',200,'USD','SURCHARGE'), ('EC','DDP',400,'USD','SURCHARGE'), ('VE','DDP',0,'USD','SURCHARGE'),
 ('BO','DDP',0,'USD','SURCHARGE'), ('PY','DDP',0,'USD','SURCHARGE'), ('UY','DDP',200,'USD','SURCHARGE'),
 ('GY','DDP',0,'USD','SURCHARGE'), ('SR','DDP',0,'USD','SURCHARGE'), ('GT','DDP',0,'USD','SURCHARGE'),
 ('CR','DDP',0,'USD','SURCHARGE'), ('PA','DDP',0,'USD','SURCHARGE'), ('DO','DDP',200,'USD','SURCHARGE'),
 ('PR','DDP',0,'USD','SURCHARGE'), ('HN','DDP',0,'USD','SURCHARGE'), ('SV','DDP',0,'USD','SURCHARGE'),
 ('NI','DDP',0,'USD','SURCHARGE'), ('BZ','DDP',0,'USD','SURCHARGE'), ('JM','DDP',0,'USD','SURCHARGE'),
 ('TT','DDP',0,'USD','SURCHARGE'), ('BS','DDP',0,'USD','SURCHARGE'), ('BB','DDP',0,'USD','SURCHARGE'),
 ('HT','DDP',0,'USD','SURCHARGE'),
 -- === Oriente Medio (divisas locales no están en currency_rate → umbral en su equivalente USD) ===
 ('AE','DDP',82,'USD','SURCHARGE'),    -- EAU: ~300 AED
 ('SA','DDP',71,'USD','SURCHARGE'),    -- Arabia Saudí: ~266 SAR
 ('IL','DDP',75,'USD','SURCHARGE'),    -- Israel: 75 USD
 ('QA','DDP',0,'USD','SURCHARGE'), ('KW','DDP',0,'USD','SURCHARGE'), ('BH','DDP',0,'USD','SURCHARGE'),
 ('OM','DDP',0,'USD','SURCHARGE'), ('JO','DDP',0,'USD','SURCHARGE'), ('LB','DDP',0,'USD','SURCHARGE'),
 -- === Oceanía ===
 ('AU','DDP',1000,'AUD','SURCHARGE'),  -- Australia: Low Value Goods, 1.000 AUD (GST 10% lo cobra el vendedor)
 ('NZ','DDP',600,'USD','SURCHARGE'),   -- Nueva Zelanda: 1.000 NZD (NZD no está en currency_rate → ~600 USD)
 ('FJ','DDP',0,'USD','SURCHARGE'), ('PG','DDP',0,'USD','SURCHARGE')
ON CONFLICT (country_code) DO NOTHING;
