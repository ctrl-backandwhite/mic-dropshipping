--liquibase formatted sql

--changeset nexadrop:v168-canal-del-pago
--comment PayPal recibia SIEMPRE una direccion de retorno de la web, tambien cuando quien pagaba estaba en
--la aplicacion movil. La app abre la aprobacion en la vista de navegador del sistema y esa vista solo se
--cierra sola cuando la navegacion llega a SU esquema (nx036://). Como la vuelta iba al escaparate web, la
--vista nunca se cerraba: la persona la cerraba a mano y la aplicacion lo interpretaba como CANCELADO
--habiendo pagado. El pedido quedaba pendiente y se podia volver a pagar.
--
--Para arreglarlo hay que saber, al cerrar el cobro contra la pasarela, desde donde se abrio. Se guarda en
--el pago porque la respuesta de PayPal llega despues, en otra peticion, y para entonces ya no hay ninguna
--cabecera que mirar.
--
--Es un IDENTIFICADOR CERRADO (WEB / MOBILE), nunca una URL. El cliente dice "mobile" y el servidor traduce
--ese valor a una direccion que el mismo tiene configurada; aceptar la direccion del cliente convertiria el
--cobro en un redirector abierto hacia el dominio de quien atacara. Mismo criterio que en el login social.
--
--El valor por defecto va tambien en la entidad Java: un DEFAULT de la columna no salva de un INSERT que
--mande NULL de forma explicita, que es lo que hace JPA con un campo sin inicializar.

ALTER TABLE payment ADD COLUMN IF NOT EXISTS client_target VARCHAR(16) NOT NULL DEFAULT 'WEB';
