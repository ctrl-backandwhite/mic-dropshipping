--liquibase formatted sql

--changeset nexadrop:v66-001 splitStatements:true endDelimiter:;
--comment: Impuesto (IVA/VAT/GST/Sales Tax) por país, para TODOS los países donde se vende (los mismos a los que envía Cainiao = los del selector de regiones). Tasa estándar de cada país en basis points (21% = 2100). Activos. Idempotente: no duplica los que ya existan. El admin puede ajustar cada tasa después.
INSERT INTO country_tax_rate (id, country_code, label, rate_bps, active)
SELECT gen_random_uuid(), v.code, v.label, v.bps, true
FROM (VALUES
    ('ES', 'IVA',       2100),  -- España
    ('FR', 'TVA',       2000),  -- Francia
    ('DE', 'MwSt',      1900),  -- Alemania
    ('IT', 'IVA',       2200),  -- Italia
    ('PT', 'IVA',       2300),  -- Portugal
    ('NL', 'BTW',       2100),  -- Países Bajos
    ('BE', 'TVA',       2100),  -- Bélgica
    ('IE', 'VAT',       2300),  -- Irlanda
    ('PL', 'VAT',       2300),  -- Polonia
    ('SE', 'Moms',      2500),  -- Suecia
    ('GB', 'VAT',       2000),  -- Reino Unido
    ('AR', 'IVA',       2100),  -- Argentina
    ('CL', 'IVA',       1900),  -- Chile
    ('CO', 'IVA',       1900),  -- Colombia
    ('PE', 'IGV',       1800),  -- Perú
    ('MX', 'IVA',       1600),  -- México
    ('BR', 'IVA',       1700),  -- Brasil (representativo ICMS)
    ('US', 'Sales Tax',    0),  -- EE. UU. (sin IVA federal; varía por estado)
    ('CA', 'GST',        500),  -- Canadá (federal)
    ('AU', 'GST',       1000),  -- Australia
    ('NZ', 'GST',       1500),  -- Nueva Zelanda
    ('SG', 'GST',        900),  -- Singapur
    ('JP', 'Tax',       1000),  -- Japón (消費税)
    ('KR', 'VAT',       1000),  -- Corea del Sur
    ('AE', 'VAT',        500),  -- Emiratos Árabes Unidos
    ('SA', 'VAT',       1500),  -- Arabia Saudí
    ('IL', 'VAT',       1700),  -- Israel
    ('ZA', 'VAT',       1500),  -- Sudáfrica
    ('ID', 'PPN',       1100),  -- Indonesia
    ('PH', 'VAT',       1200),  -- Filipinas
    ('MY', 'SST',       1000),  -- Malasia
    ('TH', 'VAT',        700),  -- Tailandia
    ('VN', 'VAT',        800)   -- Vietnam
) AS v(code, label, bps)
WHERE NOT EXISTS (SELECT 1 FROM country_tax_rate t WHERE t.country_code = v.code);
