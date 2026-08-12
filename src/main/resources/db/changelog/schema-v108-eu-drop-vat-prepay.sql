--liquibase formatted sql

--changeset nexadrop:v108-001-eu-drop-vat-prepay splitStatements:false
-- La comisión del 2% de prepago de IVA de YunExpress deja de cobrarse como recargo en el checkout porque
-- ya está contemplada en el MARGEN de la UE (104% = base ~100% + ~4 puntos ≈ 2% de la venta; ver v107). El
-- vendedor paga ese 2% a YunExpress con su margen. Cobrarlo también en el checkout lo duplicaría al cliente.
-- Se pone a 0 en los 27 países UE; el arancel de 3 EUR por artículo (per_article_fee) se mantiene.
UPDATE country_customs_rule
   SET vat_prepay_percent_bps = 0
 WHERE country_code IN ('AT','BE','BG','HR','CY','CZ','DK','EE','FI','FR','DE','GR','HU','IE','IT',
                        'LV','LT','LU','MT','NL','PL','PT','RO','SK','SI','ES','SE');
