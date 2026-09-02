--liquibase formatted sql

--changeset nexadrop:v164-espejado-de-video
--comment Los videos NO se espejaban. La ficha llevaba la direccion de cloud.video.taobao.com tal cual y
--el navegador del comprador iba a pedirsela a Alibaba: cada reproduccion salia de sus servidores, no de
--nuestro borde, y el dia que ellos quiten el video o cierren el enlace, la ficha se queda sin el sin que
--nadie se entere. Las imagenes llevaban resuelto esto desde el principio; los videos se habian quedado
--fuera.
--Estas columnas son las mismas que ya tiene product_image, con los mismos nombres, para que el servicio
--de espejado de video sea el de imagenes con otra tabla y no un mecanismo nuevo que aprender:
--  video_cdn_url         donde queda en NUESTRO almacenamiento (la vista la prefiere sobre video_url)
--  video_mirror_status   PENDING -> MIRRORED | FAILED
--  video_bytes/hash      para auditar y no subir dos veces el mismo fichero
--  video_mirror_attempts para que un video que no se puede traer deje de intentarse para siempre

ALTER TABLE product ADD COLUMN IF NOT EXISTS video_cdn_url VARCHAR(800);
ALTER TABLE product ADD COLUMN IF NOT EXISTS video_mirror_status VARCHAR(20);
ALTER TABLE product ADD COLUMN IF NOT EXISTS video_bytes BIGINT;
ALTER TABLE product ADD COLUMN IF NOT EXISTS video_hash VARCHAR(64);
ALTER TABLE product ADD COLUMN IF NOT EXISTS video_mirror_attempts INT DEFAULT 0;
ALTER TABLE product ADD COLUMN IF NOT EXISTS video_mirrored_at TIMESTAMP WITH TIME ZONE;

--changeset nexadrop:v164-encolar-videos-existentes runAlways:true
--comment Encola lo que haya que espejar: los que tienen un video de fuera y todavia no estan en nuestro
--almacenamiento. runAlways porque en produccion los productos entran por el bus DESPUES de que Liquibase
--haya pasado por aqui: sin esto, un video que llegue manana no se encolaria nunca. Solo toca los que no
--tienen estado, asi que no pisa a los que ya fallaron ni reencola lo ya espejado.

UPDATE product
   SET video_mirror_status = 'PENDING', video_mirror_attempts = 0
 WHERE video_url IS NOT NULL
   AND video_url <> ''
   AND video_url LIKE 'http%'
   AND video_cdn_url IS NULL
   AND video_mirror_status IS NULL;

--changeset nexadrop:v164-indice-de-barrido
--comment El barrido pregunta cada pocos segundos por los pendientes. Sin indice es un repaso a la tabla
--entera de productos cada vez.

CREATE INDEX IF NOT EXISTS idx_product_video_mirror_status
    ON product (video_mirror_status) WHERE video_mirror_status IS NOT NULL;
