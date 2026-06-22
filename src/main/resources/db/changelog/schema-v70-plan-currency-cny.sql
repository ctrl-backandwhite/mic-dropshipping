--liquibase formatted sql

--changeset nexadrop:v70-plan-currency-cny
-- Los planes se cargan en la moneda de 1688 (CNY), igual que los productos; la app convierte a la
-- moneda de display del usuario (X-Currency) al servir, y a USD para el cobro en Stripe. Aquí solo se
-- fija la moneda de origen a CNY; los IMPORTES en CNY los ajusta el admin desde la página de planes.
UPDATE subscription_plan SET currency = 'CNY' WHERE currency IS NULL OR currency <> 'CNY';
ALTER TABLE subscription_plan ALTER COLUMN currency SET DEFAULT 'CNY';
