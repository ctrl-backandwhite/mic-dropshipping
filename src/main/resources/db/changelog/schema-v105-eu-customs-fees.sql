--liquibase formatted sql

--changeset nexadrop:v105-001-eu-customs-fees splitStatements:false
-- Recargos de despacho DDP que YunExpress repercute en la UE y que hay que trasladar al cliente para no
-- comer margen (confirmado por el gestor, 12-ago-2026):
--   1) Comisión del prepago de IVA (cuando el vendedor NO tiene IOSS): 2% sobre el valor declarado, ADEMÁS
--      del IVA del país. Configurable por país: se pone a 0 en cuanto se registre un IOSS.
--   2) Arancel temporal de la UE: 3 EUR por ARTÍCULO (producto distinto por HS+nombre+origen; varias
--      unidades del mismo producto = 1 artículo).
-- Ambos van al recargo de despacho (se suman al envío, NO a la base del IVA). Neutro fuera de la UE.
ALTER TABLE country_customs_rule
    ADD COLUMN IF NOT EXISTS vat_prepay_percent_bps  integer       NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS per_article_fee_amount   numeric(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS per_article_fee_currency varchar(3)    NOT NULL DEFAULT 'EUR';

-- UE-27: 2% de comisión de prepago (sin IOSS) + 3 EUR por artículo.
UPDATE country_customs_rule
   SET vat_prepay_percent_bps = 200,
       per_article_fee_amount = 3.00,
       per_article_fee_currency = 'EUR'
 WHERE country_code IN ('AT','BE','BG','HR','CY','CZ','DK','EE','FI','FR','DE','GR','HU','IE','IT',
                        'LV','LT','LU','MT','NL','PL','PT','RO','SK','SI','ES','SE');
