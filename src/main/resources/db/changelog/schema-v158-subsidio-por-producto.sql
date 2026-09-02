--liquibase formatted sql

--changeset nexadrop:v158-subsidio-por-producto
--comment Bolsas de subvencion por producto en CNY, default 0. shipping_user_cny cubre el porte y
--comment duty_user_cny el arancel; las asigna el admin y sustituyen al calculo automatico anterior,
--comment que financiaba la subvencion con el margen de ganancia del pedido.
ALTER TABLE product ADD COLUMN shipping_user_cny numeric(12, 4) NOT NULL DEFAULT 0;
ALTER TABLE product ADD COLUMN duty_user_cny numeric(12, 4) NOT NULL DEFAULT 0;
