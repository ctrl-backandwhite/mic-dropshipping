--liquibase formatted sql

--changeset nexadrop:v89-outbound-email-inline-images
-- Imágenes que viajan DENTRO del correo como adjuntos inline (Content-ID), no por URL remota.
--
-- Las fotos de producto de la factura se enviaban con la URL pública del storage. Eso falla en dos casos
-- reales: (1) en local la URL es http://localhost:9100/... y los servidores de Gmail —que descargan la
-- imagen por proxy— no pueden alcanzarla; (2) Outlook y Apple Mail con protección de privacidad bloquean
-- las imágenes remotas por defecto, así que el cliente no ve las fotos aunque la URL sea correcta.
--
-- Los iconos del email ya usaban CID (se resolvían del classpath) y por eso SÍ se veían. Esta columna
-- extiende ese mecanismo a imágenes que viven en el storage: guarda un JSON {cid: urlPublica} y el
-- dispatcher descarga cada una del bucket (por cliente S3 interno, no por HTTP público), la reduce a
-- miniatura y la adjunta como inline.
ALTER TABLE outbound_email ADD COLUMN IF NOT EXISTS inline_images TEXT;
