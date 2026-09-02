--liquibase formatted sql

--changeset nexadrop:v163-quitar-recursos-de-interfaz runAlways:true
--comment Entre las fotos de los productos se habian colado piezas de la interfaz de 1688: una equis
--gris de 200x200 y 1041 bytes en 361 productos, una silueta de vaca en 2 y el sello CCC en 1. El
--comprador las veia en la galeria como una imagen mas del producto.
--No era un fallo de descarga: estaban las tres MIRRORED, porque la imagen existe y se espeja
--perfectamente. Simplemente no son fotos. Se distinguen por la ruta: las fotos de producto vienen de
--.../img/ibank/O1CN..., y por alicdn.com/tfs/ solo bajan recursos de la web del proveedor.
--Siempre ocupaban la ULTIMA posicion y ninguna era la principal, asi que borrarlas no deja huecos en
--medio ni ningun producto sin foto: a los afectados les quedan entre 3 y 17.
--runAlways de red de seguridad: la puerta de entrada ya las rechaza (BulkProductRules.isProductPhoto,
--aplicado en el importador y en la ingesta), pero produccion se alimenta del bus y esto garantiza que
--ninguna sobreviva a un evento antiguo reprocesado.

DELETE FROM product_image WHERE source_url LIKE '%alicdn.com/tfs/%';
