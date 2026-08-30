--liquibase formatted sql

--changeset nexadrop:v157-surcharge-cny
--comment Recargo fijo por producto en CNY (surcharge_cny), default 0. Lo edita el admin por
--producto, categoria o masivamente para todo el catalogo, y se suma al precio final de venta
--(componente del desglose, igual que IVA y envio).

ALTER TABLE product ADD COLUMN surcharge_cny numeric(12, 4) NOT NULL DEFAULT 0;
