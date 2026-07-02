--liquibase formatted sql

--changeset nexadrop:v74-seed-regions-rest splitStatements:true endDelimiter:;
-- Regiones (estado/provincia/región) del RESTO de países para el dropdown del checkout/direcciones.
-- NO llevan tasa propia (rate_bps NULL) → usan el IVA NACIONAL del país (solo US/CA/BR varían por región).
-- En países con muchísimas divisiones se usan REGIONES MACRO (JP 8, TH 6, VN 8, ID 7). position=0 → el
-- backend las ordena alfabéticamente por nombre. Idempotente. El admin puede ajustar/añadir desde la BD.
INSERT INTO country_region (id, country_code, region_code, region_name, rate_bps, active, position)
SELECT gen_random_uuid(), v.cc, v.code, v.name, NULL, true, 0
FROM (VALUES
    -- España (comunidades autónomas + Ceuta/Melilla)
    ('ES','AN','Andalucía'),('ES','AR','Aragón'),('ES','AS','Asturias'),('ES','CB','Cantabria'),
    ('ES','CL','Castilla y León'),('ES','CM','Castilla-La Mancha'),('ES','CT','Cataluña'),('ES','CE','Ceuta'),
    ('ES','EX','Extremadura'),('ES','GA','Galicia'),('ES','IB','Islas Baleares'),('ES','CN','Canarias'),
    ('ES','RI','La Rioja'),('ES','MD','Madrid'),('ES','MC','Murcia'),('ES','NC','Navarra'),
    ('ES','PV','País Vasco'),('ES','VC','Comunidad Valenciana'),('ES','ML','Melilla'),
    -- Francia (régions metropolitanas)
    ('FR','ARA','Auvergne-Rhône-Alpes'),('FR','BFC','Bourgogne-Franche-Comté'),('FR','BRE','Bretagne'),
    ('FR','CVL','Centre-Val de Loire'),('FR','COR','Corse'),('FR','GES','Grand Est'),('FR','HDF','Hauts-de-France'),
    ('FR','IDF','Île-de-France'),('FR','NOR','Normandie'),('FR','NAQ','Nouvelle-Aquitaine'),('FR','OCC','Occitanie'),
    ('FR','PDL','Pays de la Loire'),('FR','PAC','Provence-Alpes-Côte d''Azur'),
    -- Alemania (Bundesländer)
    ('DE','BW','Baden-Württemberg'),('DE','BY','Bayern'),('DE','BE','Berlin'),('DE','BB','Brandenburg'),
    ('DE','HB','Bremen'),('DE','HH','Hamburg'),('DE','HE','Hessen'),('DE','MV','Mecklenburg-Vorpommern'),
    ('DE','NI','Niedersachsen'),('DE','NW','Nordrhein-Westfalen'),('DE','RP','Rheinland-Pfalz'),('DE','SL','Saarland'),
    ('DE','SN','Sachsen'),('DE','ST','Sachsen-Anhalt'),('DE','SH','Schleswig-Holstein'),('DE','TH','Thüringen'),
    -- Italia (regioni)
    ('IT','ABR','Abruzzo'),('IT','BAS','Basilicata'),('IT','CAL','Calabria'),('IT','CAM','Campania'),
    ('IT','EMR','Emilia-Romagna'),('IT','FVG','Friuli-Venezia Giulia'),('IT','LAZ','Lazio'),('IT','LIG','Liguria'),
    ('IT','LOM','Lombardia'),('IT','MAR','Marche'),('IT','MOL','Molise'),('IT','PMN','Piemonte'),('IT','PUG','Puglia'),
    ('IT','SAR','Sardegna'),('IT','SIC','Sicilia'),('IT','TOS','Toscana'),('IT','TAA','Trentino-Alto Adige'),
    ('IT','UMB','Umbria'),('IT','VDA','Valle d''Aosta'),('IT','VEN','Veneto'),
    -- Portugal (distritos + regiões autónomas)
    ('PT','AV','Aveiro'),('PT','BE','Beja'),('PT','BR','Braga'),('PT','BG','Bragança'),('PT','CB','Castelo Branco'),
    ('PT','CO','Coimbra'),('PT','EV','Évora'),('PT','FA','Faro'),('PT','GU','Guarda'),('PT','LE','Leiria'),
    ('PT','LI','Lisboa'),('PT','PA','Portalegre'),('PT','PO','Porto'),('PT','SA','Santarém'),('PT','SE','Setúbal'),
    ('PT','VC','Viana do Castelo'),('PT','VR','Vila Real'),('PT','VI','Viseu'),('PT','AC','Açores'),('PT','MA','Madeira'),
    -- Países Bajos (provincies)
    ('NL','DR','Drenthe'),('NL','FL','Flevoland'),('NL','FR','Fryslân'),('NL','GE','Gelderland'),('NL','GR','Groningen'),
    ('NL','LI','Limburg'),('NL','NB','Noord-Brabant'),('NL','NH','Noord-Holland'),('NL','OV','Overijssel'),
    ('NL','UT','Utrecht'),('NL','ZE','Zeeland'),('NL','ZH','Zuid-Holland'),
    -- Bélgica (provincias + Bruselas)
    ('BE','VAN','Antwerpen'),('BE','VBR','Vlaams-Brabant'),('BE','VLI','Limburg'),('BE','VOV','Oost-Vlaanderen'),
    ('BE','VWV','West-Vlaanderen'),('BE','WBR','Brabant wallon'),('BE','WHT','Hainaut'),('BE','WLG','Liège'),
    ('BE','WLX','Luxembourg'),('BE','WNA','Namur'),('BE','BRU','Bruxelles'),
    -- Irlanda (provincias)
    ('IE','L','Leinster'),('IE','M','Munster'),('IE','C','Connacht'),('IE','U','Ulster'),
    -- Polonia (voivodatos)
    ('PL','DS','Dolnośląskie'),('PL','KP','Kujawsko-Pomorskie'),('PL','LU','Lubelskie'),('PL','LB','Lubuskie'),
    ('PL','LD','Łódzkie'),('PL','MA','Małopolskie'),('PL','MZ','Mazowieckie'),('PL','OP','Opolskie'),
    ('PL','PK','Podkarpackie'),('PL','PD','Podlaskie'),('PL','PM','Pomorskie'),('PL','SL','Śląskie'),
    ('PL','SK','Świętokrzyskie'),('PL','WN','Warmińsko-Mazurskie'),('PL','WP','Wielkopolskie'),('PL','ZP','Zachodniopomorskie'),
    -- Suecia (län)
    ('SE','AB','Stockholm'),('SE','C','Uppsala'),('SE','D','Södermanland'),('SE','E','Östergötland'),
    ('SE','F','Jönköping'),('SE','G','Kronoberg'),('SE','H','Kalmar'),('SE','I','Gotland'),('SE','K','Blekinge'),
    ('SE','M','Skåne'),('SE','N','Halland'),('SE','O','Västra Götaland'),('SE','S','Värmland'),('SE','T','Örebro'),
    ('SE','U','Västmanland'),('SE','W','Dalarna'),('SE','X','Gävleborg'),('SE','Y','Västernorrland'),
    ('SE','Z','Jämtland'),('SE','AC','Västerbotten'),('SE','BD','Norrbotten'),
    -- Reino Unido (naciones)
    ('GB','ENG','England'),('GB','SCT','Scotland'),('GB','WLS','Wales'),('GB','NIR','Northern Ireland'),
    -- Argentina (provincias + CABA)
    ('AR','C','Ciudad Autónoma de Buenos Aires'),('AR','B','Buenos Aires'),('AR','K','Catamarca'),('AR','H','Chaco'),
    ('AR','U','Chubut'),('AR','X','Córdoba'),('AR','W','Corrientes'),('AR','E','Entre Ríos'),('AR','P','Formosa'),
    ('AR','Y','Jujuy'),('AR','L','La Pampa'),('AR','F','La Rioja'),('AR','M','Mendoza'),('AR','N','Misiones'),
    ('AR','Q','Neuquén'),('AR','R','Río Negro'),('AR','A','Salta'),('AR','J','San Juan'),('AR','D','San Luis'),
    ('AR','Z','Santa Cruz'),('AR','S','Santa Fe'),('AR','G','Santiago del Estero'),('AR','V','Tierra del Fuego'),('AR','T','Tucumán'),
    -- Chile (regiones)
    ('CL','AP','Arica y Parinacota'),('CL','TA','Tarapacá'),('CL','AN','Antofagasta'),('CL','AT','Atacama'),
    ('CL','CO','Coquimbo'),('CL','VS','Valparaíso'),('CL','RM','Región Metropolitana'),('CL','LI','O''Higgins'),
    ('CL','ML','Maule'),('CL','NB','Ñuble'),('CL','BI','Biobío'),('CL','AR','La Araucanía'),('CL','LR','Los Ríos'),
    ('CL','LL','Los Lagos'),('CL','AI','Aysén'),('CL','MA','Magallanes'),
    -- Colombia (departamentos + Bogotá)
    ('CO','DC','Bogotá D.C.'),('CO','AMA','Amazonas'),('CO','ANT','Antioquia'),('CO','ARA','Arauca'),('CO','ATL','Atlántico'),
    ('CO','BOL','Bolívar'),('CO','BOY','Boyacá'),('CO','CAL','Caldas'),('CO','CAQ','Caquetá'),('CO','CAS','Casanare'),
    ('CO','CAU','Cauca'),('CO','CES','Cesar'),('CO','CHO','Chocó'),('CO','CORD','Córdoba'),('CO','CUN','Cundinamarca'),
    ('CO','GUA','Guainía'),('CO','GUV','Guaviare'),('CO','HUI','Huila'),('CO','LAG','La Guajira'),('CO','MAG','Magdalena'),
    ('CO','MET','Meta'),('CO','NAR','Nariño'),('CO','NSA','Norte de Santander'),('CO','PUT','Putumayo'),('CO','QUI','Quindío'),
    ('CO','RIS','Risaralda'),('CO','SAP','San Andrés y Providencia'),('CO','SAN','Santander'),('CO','SUC','Sucre'),
    ('CO','TOL','Tolima'),('CO','VAC','Valle del Cauca'),('CO','VAU','Vaupés'),('CO','VID','Vichada'),
    -- Perú (departamentos + Callao)
    ('PE','AMA','Amazonas'),('PE','ANC','Áncash'),('PE','APU','Apurímac'),('PE','ARE','Arequipa'),('PE','AYA','Ayacucho'),
    ('PE','CAJ','Cajamarca'),('PE','CAL','Callao'),('PE','CUS','Cusco'),('PE','HUV','Huancavelica'),('PE','HUC','Huánuco'),
    ('PE','ICA','Ica'),('PE','JUN','Junín'),('PE','LAL','La Libertad'),('PE','LAM','Lambayeque'),('PE','LIM','Lima'),
    ('PE','LOR','Loreto'),('PE','MDD','Madre de Dios'),('PE','MOQ','Moquegua'),('PE','PAS','Pasco'),('PE','PIU','Piura'),
    ('PE','PUN','Puno'),('PE','SAM','San Martín'),('PE','TAC','Tacna'),('PE','TUM','Tumbes'),('PE','UCA','Ucayali'),
    -- México (estados)
    ('MX','AGU','Aguascalientes'),('MX','BCN','Baja California'),('MX','BCS','Baja California Sur'),('MX','CAM','Campeche'),
    ('MX','CHP','Chiapas'),('MX','CHH','Chihuahua'),('MX','CMX','Ciudad de México'),('MX','COA','Coahuila'),('MX','COL','Colima'),
    ('MX','DUR','Durango'),('MX','GUA','Guanajuato'),('MX','GRO','Guerrero'),('MX','HID','Hidalgo'),('MX','JAL','Jalisco'),
    ('MX','MEX','México'),('MX','MIC','Michoacán'),('MX','MOR','Morelos'),('MX','NAY','Nayarit'),('MX','NLE','Nuevo León'),
    ('MX','OAX','Oaxaca'),('MX','PUE','Puebla'),('MX','QUE','Querétaro'),('MX','ROO','Quintana Roo'),('MX','SLP','San Luis Potosí'),
    ('MX','SIN','Sinaloa'),('MX','SON','Sonora'),('MX','TAB','Tabasco'),('MX','TAM','Tamaulipas'),('MX','TLA','Tlaxcala'),
    ('MX','VER','Veracruz'),('MX','YUC','Yucatán'),('MX','ZAC','Zacatecas'),
    -- Australia (estados/territorios)
    ('AU','NSW','New South Wales'),('AU','VIC','Victoria'),('AU','QLD','Queensland'),('AU','WA','Western Australia'),
    ('AU','SA','South Australia'),('AU','TAS','Tasmania'),('AU','ACT','Australian Capital Territory'),('AU','NT','Northern Territory'),
    -- Nueva Zelanda (regiones)
    ('NZ','NTL','Northland'),('NZ','AUK','Auckland'),('NZ','WKO','Waikato'),('NZ','BOP','Bay of Plenty'),('NZ','GIS','Gisborne'),
    ('NZ','HKB','Hawke''s Bay'),('NZ','TKI','Taranaki'),('NZ','MWT','Manawatū-Whanganui'),('NZ','WGN','Wellington'),
    ('NZ','TAS','Tasman'),('NZ','NSN','Nelson'),('NZ','MBH','Marlborough'),('NZ','WTC','West Coast'),('NZ','CAN','Canterbury'),
    ('NZ','OTA','Otago'),('NZ','STL','Southland'),
    -- Singapur (regiones)
    ('SG','CR','Central'),('SG','ER','East'),('SG','NR','North'),('SG','NER','North-East'),('SG','WR','West'),
    -- Japón (regiones macro)
    ('JP','HOK','Hokkaido'),('JP','TOH','Tohoku'),('JP','KAN','Kanto'),('JP','CHU','Chubu'),('JP','KIN','Kansai'),
    ('JP','CHG','Chugoku'),('JP','SHI','Shikoku'),('JP','KYU','Kyushu-Okinawa'),
    -- Corea del Sur (divisiones)
    ('KR','SEO','Seoul'),('KR','BSN','Busan'),('KR','DGU','Daegu'),('KR','ICH','Incheon'),('KR','GWJ','Gwangju'),
    ('KR','DJN','Daejeon'),('KR','USN','Ulsan'),('KR','SJG','Sejong'),('KR','GGD','Gyeonggi'),('KR','GWD','Gangwon'),
    ('KR','NCB','North Chungcheong'),('KR','SCB','South Chungcheong'),('KR','NJL','North Jeolla'),('KR','SJL','South Jeolla'),
    ('KR','NGS','North Gyeongsang'),('KR','SGS','South Gyeongsang'),('KR','JEJ','Jeju'),
    -- Emiratos Árabes Unidos (emiratos)
    ('AE','AZ','Abu Dhabi'),('AE','DU','Dubai'),('AE','SH','Sharjah'),('AE','AJ','Ajman'),('AE','UQ','Umm Al Quwain'),
    ('AE','RK','Ras Al Khaimah'),('AE','FU','Fujairah'),
    -- Arabia Saudí (regiones)
    ('SA','RI','Riyadh'),('SA','MK','Makkah'),('SA','MD','Madinah'),('SA','QA','Qassim'),('SA','EP','Eastern Province'),
    ('SA','AS','Asir'),('SA','TB','Tabuk'),('SA','HA','Ha''il'),('SA','NB','Northern Borders'),('SA','JZ','Jazan'),
    ('SA','NJ','Najran'),('SA','BH','Al Bahah'),('SA','JF','Al Jawf'),
    -- Israel (distritos)
    ('IL','JM','Jerusalem'),('IL','NO','Northern'),('IL','HA','Haifa'),('IL','CE','Central'),('IL','TA','Tel Aviv'),('IL','SO','Southern'),
    -- Sudáfrica (provincias)
    ('ZA','EC','Eastern Cape'),('ZA','FS','Free State'),('ZA','GP','Gauteng'),('ZA','KZN','KwaZulu-Natal'),('ZA','LP','Limpopo'),
    ('ZA','MP','Mpumalanga'),('ZA','NC','Northern Cape'),('ZA','NW','North West'),('ZA','WC','Western Cape'),
    -- Indonesia (grupos de islas, macro)
    ('ID','SUM','Sumatra'),('ID','JAW','Java'),('ID','KAL','Kalimantan'),('ID','SUL','Sulawesi'),
    ('ID','BNT','Bali y Nusa Tenggara'),('ID','MAL','Maluku'),('ID','PAP','Papua'),
    -- Filipinas (regiones administrativas)
    ('PH','NCR','Metro Manila (NCR)'),('PH','CAR','Cordillera (CAR)'),('PH','R1','Ilocos'),('PH','R2','Cagayan Valley'),
    ('PH','R3','Central Luzon'),('PH','R4A','Calabarzon'),('PH','R4B','Mimaropa'),('PH','R5','Bicol'),
    ('PH','R6','Western Visayas'),('PH','R7','Central Visayas'),('PH','R8','Eastern Visayas'),('PH','R9','Zamboanga Peninsula'),
    ('PH','R10','Northern Mindanao'),('PH','R11','Davao'),('PH','R12','Soccsksargen'),('PH','R13','Caraga'),('PH','BAR','BARMM'),
    -- Malasia (estados + territorios federales)
    ('MY','JHR','Johor'),('MY','KDH','Kedah'),('MY','KTN','Kelantan'),('MY','MLK','Malacca'),('MY','NSN','Negeri Sembilan'),
    ('MY','PHG','Pahang'),('MY','PNG','Penang'),('MY','PRK','Perak'),('MY','PLS','Perlis'),('MY','SBH','Sabah'),
    ('MY','SWK','Sarawak'),('MY','SGR','Selangor'),('MY','TRG','Terengganu'),('MY','KUL','Kuala Lumpur'),('MY','LBN','Labuan'),('MY','PJY','Putrajaya'),
    -- Tailandia (regiones macro)
    ('TH','N','Northern'),('TH','NE','Northeastern'),('TH','C','Central'),('TH','E','Eastern'),('TH','W','Western'),('TH','S','Southern'),
    -- Vietnam (regiones macro)
    ('VN','NE','Northeast'),('VN','NW','Northwest'),('VN','RRD','Red River Delta'),('VN','NCC','North Central Coast'),
    ('VN','SCC','South Central Coast'),('VN','CH','Central Highlands'),('VN','SE','Southeast'),('VN','MRD','Mekong River Delta')
) AS v(cc, code, name)
WHERE NOT EXISTS (SELECT 1 FROM country_region r WHERE r.country_code = v.cc AND r.region_code = v.code);
