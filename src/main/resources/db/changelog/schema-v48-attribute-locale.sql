--liquibase formatted sql

--changeset nexadrop:v48-attribute-locale
-- DROP-672: atributos multi-idioma. locale NULL = neutral (facetas/filtrado); un locale concreto
-- (es/en/pt/zh) aporta el valor traducido para mostrar. Las facetas siguen usando los neutrales.
ALTER TABLE product_attribute ADD COLUMN IF NOT EXISTS locale varchar(8);
