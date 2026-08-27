--liquibase formatted sql

--changeset nexadrop:v148-iva-destinos-restantes splitStatements:false
--
-- Cuarenta destinos se estaban vendiendo sin cobrar impuesto, y en silencio.
--
-- La tienda tiene 90 destinos habilitados en `cainiao_shipping_zone` desde que la v144 contrastó la
-- cobertura contra las tarifas reales del transportista, pero `country_tax_rate` solo tenía 50 filas: la
-- semilla original de la v66 (33 países) más los 17 de la UE que completó la v104. Los 40 restantes
-- —Balcanes, microestados, Oriente Medio, Centroamérica, el Caribe y el Cono Sur— nunca llegaron a tener
-- tipo.
--
-- Lo que costaba: `CountryTaxService.rateBpsFor` devuelve 0 tanto si el país tiene un tipo del 0% como si
-- NO tiene fila, y `computeTaxCents` corta en `bps <= 0` sin avisar a nadie. Es decir, el pedido a Noruega
-- se cobraba sin el 25% de MVA y el pedido a Uruguay sin el 22% de IVA, y el checkout lo daba por bueno.
-- El impuesto no desaparece porque no se cobre: lo repercute el transportista al despachar en destino y
-- acaba saliendo del margen, o se le reclama después al cliente en la entrega, que es peor.
--
-- Decisión del dueño (19-ago-2026): se completan TODOS los destinos habilitados, aunque hoy el selector
-- solo enseñe unos pocos. Un destino que se pueda habilitar mañana desde el panel no puede depender de que
-- alguien se acuerde de darle tipo.
--
-- FUENTES. Cada tipo lleva la suya. No se ha inventado ninguno: los tipos equivocados cobran de menos
-- —y eso sale del margen— o de más al cliente, que es peor.
--
--   [PWC]   PwC Worldwide Tax Summaries, ficha «Corporate – Other taxes» del país. Consultado 19-ago-2026.
--   [VATU]  VATupdate, «Global VAT Rates by Country (2026) – Standard and Reduced Rates».
--   [TXID]  TaxID, «VAT & GST Rates by Country 2026», contrastado con [VATU] línea a línea.
--   Los destinos con autoridad fiscal citada expresamente llevan la referencia en su propia línea.
--
-- QUÉ SIGNIFICA UN 0 AQUÍ. Kuwait y Catar aparecen con 0 y con fila propia, no ausentes. Los dos firmaron
-- el marco del IVA del CCG pero ninguno lo ha puesto en vigor: Kuwait tiene la ley en trámite parlamentario
-- y Catar «no impone hoy IVA ni impuesto sobre ventas» [PWC]. La fila con 0 es la diferencia entre «este
-- país no cobra impuesto» y «a este país se le olvidó ponerle tipo», que hasta hoy eran indistinguibles.
--
-- PUERTO RICO no se resuelve por `country_region`. Ahí solo están los 50 estados y el Distrito de Columbia
-- que sembró la v73; PR viaja como país propio en `cainiao_shipping_zone` (su última milla es USPS, por eso
-- la v130 lo mantuvo), así que `rateBpsFor('PR')` cae directo a esta tabla y necesita su propia fila.
--
-- BOLIVIA va con el 13% nominal, que es el tipo que publica la norma y el que citan las fuentes. Su IVA se
-- liquida «por dentro» del precio, de modo que sobre base neta la carga efectiva sale al 14,94%; aplicar
-- ese 14,94% aquí sería cobrarle al cliente un tipo que ninguna autoridad publica como tal.
--
-- Idempotente y no destructivo: `WHERE NOT EXISTS` deja intactos los 50 países que ya tenían tipo, incluidos
-- los que el administrador haya ajustado a mano desde el panel. Ejecutarlo dos veces no cambia nada.

INSERT INTO country_tax_rate (id, country_code, label, rate_bps, active, created_at, updated_at,
                              created_by, updated_by)
SELECT gen_random_uuid(), v.code, v.label, v.bps, TRUE, NOW(), NOW(),
       'v148-iva-destinos-restantes', 'v148-iva-destinos-restantes'
FROM (VALUES
    -- Europa no comunitaria y microestados
    ('AL', 'TVSH',  2000),  -- Albania 20%                                                  [VATU][TXID]
    ('BA', 'PDV',   1700),  -- Bosnia y Herzegovina 17% (tipo único)                        [VATU][TXID]
    ('CH', 'MWST',   810),  -- Suiza 8,1% desde el 1-ene-2024                               [VATU][TXID]
    ('IS', 'VSK',   2400),  -- Islandia 24%                                                 [VATU][TXID]
    ('LI', 'MWST',   810),  -- Liechtenstein 8,1%: unión aduanera con Suiza, tipos idénticos
                            -- y subida simultánea el 1-ene-2024 (Steuerverwaltung, llv.li)
    ('MC', 'TVA',   2000),  -- Mónaco 20%: aplica la TVA francesa por la convención fiscal
                            -- franco-monegasca de 18-may-1963 (monentreprise.gouv.mc)
    ('MD', 'TVA',   2000),  -- Moldavia 20%                                                 [VATU][TXID]
    ('ME', 'PDV',   2100),  -- Montenegro 21%                                               [VATU][TXID]
    ('MK', 'DDV',   1800),  -- Macedonia del Norte 18%                                      [VATU][TXID]
    ('NO', 'MVA',   2500),  -- Noruega 25%                                                  [VATU][TXID]
    ('RS', 'PDV',   2000),  -- Serbia 20%                                                   [VATU][TXID]
    ('UA', 'PDV',   2000),  -- Ucrania 20%                                                  [VATU][TXID]

    -- Oriente Medio y Turquía
    ('BH', 'VAT',   1000),  -- Baréin 10% desde el 1-ene-2022 (antes 5%)                    [VATU][TXID]
    ('JO', 'GST',   1600),  -- Jordania 16% (impuesto general sobre ventas)                 [VATU][TXID]
    ('KW', 'Sin IVA',  0),  -- Kuwait: el marco del CCG sigue en trámite; no hay IVA en vigor      [PWC]
    ('LB', 'TVA',   1100),  -- Líbano 11%                                                   [VATU][TXID]
    ('OM', 'VAT',    500),  -- Omán 5%                                                      [VATU][TXID]
    ('QA', 'Sin IVA',  0),  -- Catar: «no VAT or sales tax» hoy; el 5% del CCG aún no entra   [PWC]
    ('TR', 'KDV',   2000),  -- Turquía 20% desde el 10-jul-2023 (antes 18%)            [PWC][VATU][TXID]

    -- Centroamérica y el Caribe
    ('BB', 'VAT',   1750),  -- Barbados 17,5%                                                     [PWC]
    ('BS', 'VAT',   1000),  -- Bahamas 10% desde el 1-ene-2022; «standard rate of 10% and a
                            -- zero rate of 0%» (Department of Inland Revenue, inlandrevenue.finance.gov.bs)
    ('BZ', 'GST',   1250),  -- Belice 12,5% (General Sales Tax Act, Belize Tax Service)
    ('CR', 'IVA',   1300),  -- Costa Rica 13%                                               [VATU][TXID]
    ('DO', 'ITBIS', 1800),  -- República Dominicana 18%                                     [VATU][TXID]
    ('GT', 'IVA',   1200),  -- Guatemala 12%                                                 [PWC][TXID]
    ('HN', 'ISV',   1500),  -- Honduras 15% (impuesto sobre ventas)                               [PWC]
    ('JM', 'GCT',   1500),  -- Jamaica 15% (General Consumption Tax)                              [PWC]
    ('NI', 'IVA',   1500),  -- Nicaragua 15%                                                      [PWC]
    ('PA', 'ITBMS',  700),  -- Panamá 7%                                                    [VATU][TXID]
    ('PR', 'IVU',   1150),  -- Puerto Rico 11,5% = 10,5% estatal + 1% municipal, vigente desde
                            -- 2015 (Departamento de Hacienda, Código de Rentas Internas de 2011)
    ('SV', 'IVA',   1300),  -- El Salvador 13%                                                    [PWC]
    ('TT', 'VAT',   1250),  -- Trinidad y Tobago 12,5%                                            [PWC]

    -- América del Sur
    ('BO', 'IVA',   1300),  -- Bolivia 13% nominal (ver nota arriba sobre el 14,94% efectivo)     [PWC]
    ('EC', 'IVA',   1500),  -- Ecuador 15% desde abril de 2024 (antes 12%)                  [VATU][TXID]
    ('GY', 'VAT',   1400),  -- Guyana 14%                                                         [PWC]
    ('PY', 'IVA',   1000),  -- Paraguay 10%                                                 [VATU][TXID]
    ('SR', 'BTW',   1000),  -- Surinam 10% desde el 1-ene-2023 (Wet Belasting over de
                            -- Toegevoegde Waarde 2022, Belastingdienst Suriname)
    ('UY', 'IVA',   2200),  -- Uruguay 22%                                                  [VATU][TXID]
    ('VE', 'IVA',   1600),  -- Venezuela 16% (tipo general vigente; la ley admite 8%-16,5%)       [PWC]

    -- Oceanía
    ('PG', 'GST',   1000)   -- Papúa Nueva Guinea 10%                                             [PWC]
) AS v(code, label, bps)
WHERE NOT EXISTS (SELECT 1 FROM country_tax_rate t WHERE t.country_code = v.code);

-- Comprobación dentro de la propia migración: si al terminar queda algún destino a la venta sin tipo, es
-- que la lista de arriba se ha quedado corta y hay que verlo AHORA, no cuando el pedido ya esté cobrado.
-- El guardián permanente es CoberturaFiscalDestinosIT; esto solo certifica el estado del despliegue.
DO $$
DECLARE
    sin_tipo integer;
BEGIN
    SELECT count(*) INTO sin_tipo
      FROM cainiao_shipping_zone z
     WHERE z.enabled
       AND NOT EXISTS (SELECT 1 FROM country_tax_rate t WHERE t.country_code = z.country_code);
    IF sin_tipo <> 0 THEN
        RAISE EXCEPTION 'Quedan % destinos habilitados sin tipo impositivo en country_tax_rate', sin_tipo;
    END IF;
END $$;
