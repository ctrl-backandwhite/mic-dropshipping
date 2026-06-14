--liquibase formatted sql

--changeset nexadrop:v52-ship-from-widen
-- ship_from se diseñó como varchar(2) (código de país) pero representa la ubicación de envío
-- (ciudad/región, p.ej. "Jieyang, Guangdong"). Se amplía a 120 para no desbordar en la importación.
ALTER TABLE product ALTER COLUMN ship_from TYPE varchar(120);
