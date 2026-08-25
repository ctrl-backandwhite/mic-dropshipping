--liquibase formatted sql

--changeset nexadrop:v154-margen-ue-150
--comment Margen del 150 % en toda la UE-27 (antes solo Espana e Irlanda; el resto estaba al 104 %)

-- Decision del 25-ago-2026: el escaparate vende en la Union Europea con un 150 % de margen, y el resto
-- del mundo con el 120 % de la regla global. Hasta ahora solo Espana e Irlanda tenian el 150 %, y los
-- otros 25 paises se habian quedado en el 104 % con el que se sembraron en agosto, de modo que el mismo
-- articulo salia un 23 % mas barato en Berlin que en Madrid sin que nadie lo hubiera decidido.
--
-- El margen se aplica ahora sobre el desembolso completo del proveedor (base + IVA chino + porte), no
-- solo sobre la base: ver PricingService.priceForSupplierAmount.
UPDATE price_rule
   SET margin_value = 150.0000,
       description  = 'Margen UE 150 % sobre base + IVA chino + porte',
       updated_at   = now(),
       updated_by   = 'v154-margen-ue-150'
 WHERE channel = 'STOREFRONT'
   AND active = true
   AND country_code IN ('AT','BE','BG','CY','CZ','DE','DK','EE','ES','FI','FR','GR','HR','HU','IE','IT',
                        'LT','LU','LV','MT','NL','PL','PT','RO','SE','SI','SK');

-- La regla global sin pais es la del "resto del mundo": se deja en el 120 % y solo se le corrige el texto,
-- que no decia sobre que se aplicaba.
UPDATE price_rule
   SET description = 'Margen global 120 % sobre base + IVA chino + porte (resto del mundo)',
       updated_at  = now(),
       updated_by  = 'v154-margen-ue-150'
 WHERE channel = 'STOREFRONT'
   AND active = true
   AND country_code IS NULL;

--rollback UPDATE price_rule SET margin_value = 104.0000 WHERE channel = 'STOREFRONT' AND active = true AND country_code IN ('AT','BE','BG','CY','CZ','DE','DK','EE','FI','FR','GR','HR','HU','IT','LT','LU','LV','MT','NL','PL','PT','RO','SE','SI','SK');
