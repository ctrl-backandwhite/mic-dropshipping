--liquibase formatted sql

--changeset nexadrop:v73-001-israel-vat-fix
-- Israel subió el IVA del 17% al 18% el 1-ene-2025. Corregimos la tasa nacional.
UPDATE country_tax_rate SET rate_bps = 1800 WHERE country_code = 'IL';

--changeset nexadrop:v73-002-country-region-table
-- Regiones (estado/provincia) por país: alimentan el dropdown del checkout y, donde aplica, llevan su
-- propia tasa de impuesto (rate_bps). Si rate_bps es NULL, se usa la tasa NACIONAL de country_tax_rate
-- (la mayoría de países tienen IVA único; solo US/CA/BR varían por región). El admin puede ajustarlas.
CREATE TABLE IF NOT EXISTS country_region (
    id           uuid PRIMARY KEY,
    country_code varchar(2)   NOT NULL,
    region_code  varchar(10)  NOT NULL,
    region_name  varchar(120) NOT NULL,
    rate_bps     integer,
    active       boolean      NOT NULL DEFAULT true,
    position     integer      NOT NULL DEFAULT 0,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    created_by   varchar(120),
    updated_by   varchar(120),
    CONSTRAINT uq_country_region UNIQUE (country_code, region_code)
);
CREATE INDEX IF NOT EXISTS idx_country_region_country ON country_region (country_code, active);

--changeset nexadrop:v73-003-seed-us-states splitStatements:true endDelimiter:;
-- EE.UU.: sales tax ESTATAL base en bps (el impuesto local de ciudad/condado varía y no se incluye;
-- ajustable en admin). AK/DE/MT/NH/OR no tienen sales tax estatal (0).
INSERT INTO country_region (id, country_code, region_code, region_name, rate_bps, active, position)
SELECT gen_random_uuid(), 'US', v.code, v.name, v.bps, true, v.pos
FROM (VALUES
    ('AL','Alabama',400,1),('AK','Alaska',0,2),('AZ','Arizona',560,3),('AR','Arkansas',650,4),
    ('CA','California',725,5),('CO','Colorado',290,6),('CT','Connecticut',635,7),('DE','Delaware',0,8),
    ('DC','District of Columbia',600,9),('FL','Florida',600,10),('GA','Georgia',400,11),('HI','Hawaii',400,12),
    ('ID','Idaho',600,13),('IL','Illinois',625,14),('IN','Indiana',700,15),('IA','Iowa',600,16),
    ('KS','Kansas',650,17),('KY','Kentucky',600,18),('LA','Louisiana',500,19),('ME','Maine',550,20),
    ('MD','Maryland',600,21),('MA','Massachusetts',625,22),('MI','Michigan',600,23),('MN','Minnesota',688,24),
    ('MS','Mississippi',700,25),('MO','Missouri',423,26),('MT','Montana',0,27),('NE','Nebraska',550,28),
    ('NV','Nevada',685,29),('NH','New Hampshire',0,30),('NJ','New Jersey',663,31),('NM','New Mexico',488,32),
    ('NY','New York',400,33),('NC','North Carolina',475,34),('ND','North Dakota',500,35),('OH','Ohio',575,36),
    ('OK','Oklahoma',450,37),('OR','Oregon',0,38),('PA','Pennsylvania',600,39),('RI','Rhode Island',700,40),
    ('SC','South Carolina',600,41),('SD','South Dakota',420,42),('TN','Tennessee',700,43),('TX','Texas',625,44),
    ('UT','Utah',485,45),('VT','Vermont',600,46),('VA','Virginia',530,47),('WA','Washington',650,48),
    ('WV','West Virginia',600,49),('WI','Wisconsin',500,50),('WY','Wyoming',400,51)
) AS v(code, name, bps, pos)
WHERE NOT EXISTS (SELECT 1 FROM country_region r WHERE r.country_code = 'US' AND r.region_code = v.code);

--changeset nexadrop:v73-004-seed-ca-provinces splitStatements:true endDelimiter:;
-- Canadá: tasa COMBINADA (GST federal 5% + PST/QST provincial o HST) en bps.
INSERT INTO country_region (id, country_code, region_code, region_name, rate_bps, active, position)
SELECT gen_random_uuid(), 'CA', v.code, v.name, v.bps, true, v.pos
FROM (VALUES
    ('AB','Alberta',500,1),('BC','British Columbia',1200,2),('MB','Manitoba',1200,3),
    ('NB','New Brunswick',1500,4),('NL','Newfoundland and Labrador',1500,5),('NS','Nova Scotia',1400,6),
    ('NT','Northwest Territories',500,7),('NU','Nunavut',500,8),('ON','Ontario',1300,9),
    ('PE','Prince Edward Island',1500,10),('QC','Quebec',1498,11),('SK','Saskatchewan',1100,12),
    ('YT','Yukon',500,13)
) AS v(code, name, bps, pos)
WHERE NOT EXISTS (SELECT 1 FROM country_region r WHERE r.country_code = 'CA' AND r.region_code = v.code);

--changeset nexadrop:v73-005-seed-br-states splitStatements:true endDelimiter:;
-- Brasil: ICMS interno REPRESENTATIVO por estado en bps (varía y está en reforma IBS/CBS; ajustable).
INSERT INTO country_region (id, country_code, region_code, region_name, rate_bps, active, position)
SELECT gen_random_uuid(), 'BR', v.code, v.name, v.bps, true, v.pos
FROM (VALUES
    ('AC','Acre',1900,1),('AL','Alagoas',2000,2),('AP','Amapá',1800,3),('AM','Amazonas',2000,4),
    ('BA','Bahia',2050,5),('CE','Ceará',2000,6),('DF','Distrito Federal',2000,7),('ES','Espírito Santo',1700,8),
    ('GO','Goiás',1900,9),('MA','Maranhão',2200,10),('MT','Mato Grosso',1700,11),('MS','Mato Grosso do Sul',1700,12),
    ('MG','Minas Gerais',1800,13),('PA','Pará',1900,14),('PB','Paraíba',2000,15),('PR','Paraná',1950,16),
    ('PE','Pernambuco',2050,17),('PI','Piauí',2100,18),('RJ','Rio de Janeiro',2000,19),('RN','Rio Grande do Norte',1800,20),
    ('RS','Rio Grande do Sul',1700,21),('RO','Rondônia',1950,22),('RR','Roraima',2000,23),('SC','Santa Catarina',1700,24),
    ('SP','São Paulo',1800,25),('SE','Sergipe',1900,26),('TO','Tocantins',2000,27)
) AS v(code, name, bps, pos)
WHERE NOT EXISTS (SELECT 1 FROM country_region r WHERE r.country_code = 'BR' AND r.region_code = v.code);
