--liquibase formatted sql

--changeset nexadrop:v104-001-eu-vat-rates splitStatements:true endDelimiter:;
--comment: Completar el IVA de los 27 países de la UE. El seed v66 solo cubría 10 (ES/FR/DE/IT/PT/NL/BE/IE/PL/SE); faltaban 17, y a esos destinos el checkout NO cobraba IVA aunque YunExpress SÍ lo repercute (fuga a cargo del vendedor). Tasas estándar oficiales, tomadas de la tabla de IVA por país de YunExpress (云途 欧洲国家对应税率表). Idempotente: no toca los que ya existen (respeta ajustes del admin); solo inserta los que faltan.
INSERT INTO country_tax_rate (id, country_code, label, rate_bps, active)
SELECT gen_random_uuid(), v.code, v.label, v.bps, true
FROM (VALUES
    ('AT', 'USt',  2000),  -- Austria 20%
    ('BG', 'VAT',  2000),  -- Bulgaria 20%
    ('HR', 'PDV',  2500),  -- Croacia 25%
    ('CY', 'VAT',  1900),  -- Chipre 19%
    ('CZ', 'DPH',  2100),  -- Chequia 21%
    ('DK', 'moms', 2500),  -- Dinamarca 25%
    ('EE', 'KM',   2400),  -- Estonia 24%
    ('FI', 'ALV',  2550),  -- Finlandia 25,5%
    ('GR', 'VAT',  2400),  -- Grecia 24%
    ('HU', 'AFA',  2700),  -- Hungría 27%
    ('LV', 'PVN',  2100),  -- Letonia 21%
    ('LT', 'PVM',  2100),  -- Lituania 21%
    ('LU', 'TVA',  1700),  -- Luxemburgo 17%
    ('MT', 'VAT',  1800),  -- Malta 18%
    ('RO', 'TVA',  2100),  -- Rumanía 21%
    ('SK', 'DPH',  2300),  -- Eslovaquia 23%
    ('SI', 'DDV',  2200)   -- Eslovenia 22%
) AS v(code, label, bps)
WHERE NOT EXISTS (SELECT 1 FROM country_tax_rate t WHERE t.country_code = v.code);
