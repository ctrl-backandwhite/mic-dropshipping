--liquibase formatted sql

--changeset nexadrop:v171-imagen-origen-espejada
--comment Memoria de que URL de origen ya se bajo y donde quedo, para no volver a bajarla nunca.
--
--Por que existe: el almacenamiento ya deduplica POR CONTENIDO -la clave es el sha256 del fichero-,
--asi que dos fichas con la misma foto ocupan un solo objeto. Pero eso ahorra DISCO, no DESCARGA: la
--imagen se baja igualmente de 1688 para poder calcular ese hash. Y el recurso escaso no es el disco.
--
--Los numeros, medidos el 18-sep-2026 con la carga en marcha: unas 52 imagenes por producto entre
--fotos, variantes y muestras de color. Con 100.000 productos del mismo punado de proveedores son
--5,2 MILLONES de descargas, a un ritmo de ~4.200/hora: 52 dias. Y muchas URLs se repiten entre
--fichas del mismo vendedor, que reutiliza sus fotos de tabla de tallas, de material o de embalaje.
--
--Con esta tabla, la segunda ficha que pida la misma URL no baja nada: se le da la cdn_url que ya
--tenemos. El ahorro no es de espacio, es de la unica cosa que no podemos acelerar comprando maquina
--- el proveedor, que ademas responde 403 a quien enlaza sus imagenes desde otra web y limita por
--tasa a quien insiste.

SET lock_timeout = '5s';

-- La clave es el sha256 de la URL, no la URL. Un indice B-tree de Postgres no admite entradas de mas
-- de ~2700 bytes, y una URL de 800 caracteres en UTF-8 puede pasar de ahi: seria una bomba de
-- relojeria que solo estallaria con una URL larga concreta, en produccion y sin avisar antes.
-- varchar(64) y NO char(64), aunque el sha256 mida siempre exactamente 64: Hibernate valida el
-- esquema al arrancar y un char lo ve como `bpchar`, que no casa con lo que declara la entidad. El
-- resultado no es un aviso: el contexto no levanta y el backend no arranca. Se descubrió aquí, en las
-- pruebas de integración, que es el único sitio donde se contrasta el mapeo contra una base real.
CREATE TABLE imagen_origen_espejada (
    url_hash    varchar(64)  NOT NULL PRIMARY KEY,
    url_origen  varchar(800) NOT NULL,
    cdn_url     varchar(800) NOT NULL,
    bytes       bigint,
    hash        varchar(80),
    ancho       integer,
    alto        integer,
    comprimida  boolean      NOT NULL DEFAULT false,
    creada_en   timestamptz  NOT NULL DEFAULT now(),
    usada_en    timestamptz  NOT NULL DEFAULT now(),
    veces       bigint       NOT NULL DEFAULT 1
);

--changeset nexadrop:v171-indice-uso-dedup
--comment Para poder medir cuanto esta ahorrando de verdad y podar lo que no se reutiliza.
CREATE INDEX idx_imagen_origen_espejada_uso ON imagen_origen_espejada (veces DESC, usada_en DESC);

--changeset nexadrop:v171-compresion-diferida
--comment Separa ESPEJAR de COMPRIMIR: la foto se ve en cuanto esta espejada, y el ahorro de disco
--llega despues.
--
--Por que: comprimir es lo caro y lo peligroso del espejado. Caro porque decodificar un JPEG grande
--cuesta CPU y memoria; peligroso porque el codificador WebP es codigo nativo y un SIGSEGV no se puede
--capturar - el 5-sep-2026 se llevo por delante la JVM entera y dejo PRE sin servir, con las dos
--replicas cayendo en bucle.
--
--Poniendo la compresion en una segunda pasada, la primera solo baja y guarda: el comprador ve la foto
--en minutos en vez de esperar a que la cola de compresion llegue a su ficha. Y si la compresion falla,
--falla sobre una imagen que YA se esta sirviendo, no sobre una que todavia no existe.
--
--NULL = espejada pero aun sin comprimir. Es el estado normal de una imagen recien llegada, no un
--error: por eso no hay columna de "pendiente", basta con la ausencia de fecha.
ALTER TABLE product_image ADD COLUMN comprimida_en timestamptz;

--changeset nexadrop:v171-indice-pendientes-de-comprimir
--comment La cola de la segunda pasada. Indice parcial: solo interesan las que faltan, y esas son
--pocas frente al total una vez el catalogo esta al dia, asi que el indice se mantiene pequeno.
CREATE INDEX idx_product_image_sin_comprimir ON product_image (id)
    WHERE mirror_status = 'MIRRORED' AND comprimida_en IS NULL;
