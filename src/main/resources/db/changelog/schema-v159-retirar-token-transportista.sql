--liquibase formatted sql

--changeset nexadrop:v159-retirar-token-transportista
--
-- Se retira la tabla del token del transportista.
--
-- La creo la v146 para CJ Dropshipping, que limitaba la autenticacion a una llamada por segundo y
-- obligaba a compartir el token entre replicas. Retirada la integracion de CJ (1-sep-2026), la entidad
-- y el repositorio que la leian ya no existen: la tabla quedaba sin un solo lector.
--
-- Se penso generica (`carrier`) por si YunExpress movia aqui su token, pero YunExpress lo mantiene en
-- memoria y no hay nada previsto que lo cambie. El dia que haga falta, se vuelve a crear: mantener una
-- tabla viva por si acaso cuesta mas que escribirla de nuevo, y confunde a quien lee el esquema.
--
-- El indice unico se va con la tabla; se nombra por claridad de lo que se esta borrando.
DROP INDEX IF EXISTS ux_carrier_token_carrier;
DROP TABLE IF EXISTS carrier_token;
