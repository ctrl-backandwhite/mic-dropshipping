--liquibase formatted sql

--changeset nexadrop:v165-anuncio-al-bus-diferido
--comment Marcar un producto como verificado tardaba varios segundos, y aplicar un recargo a un lote
--tardaba eso MULTIPLICADO por el numero de productos. El motivo no era esperar a Kafka —el envio ya
--iba diferido por la bandeja de salida— sino que anunciarAlBus construia la FICHA ENTERA dentro de la
--transaccion de la peticion: cuatro consultas mas el mapeo de los ocho idiomas, las variantes, las
--imagenes y las resenas, todo serializado a JSON antes de responder.
--
--Con estas columnas la peticion solo MARCA que hay algo que anunciar —un update de una columna, sin
--consultas ni serializacion— y responde. Un barrido programado construye la ficha y la publica.
--
--Se marca dentro de la MISMA transaccion que el cambio a proposito: es lo que garantiza que un
--producto certificado no pueda quedarse sin anunciar. Publicar despues del commit habria sido mas
--simple, pero deja una ventana en la que el proceso muere y el producto no llega nunca a produccion
--sin que nadie se entere.
--
--  bus_estado    PENDIENTE -> ANUNCIADO | FALLIDO   (nulo = no hay nada que anunciar)
--  bus_intentos  para dejar de reintentar lo que no se puede publicar
--  bus_error     el motivo del ultimo fallo, para poder ENSENARLO en el panel

ALTER TABLE product ADD COLUMN IF NOT EXISTS bus_estado VARCHAR(20);
ALTER TABLE product ADD COLUMN IF NOT EXISTS bus_intentos INT DEFAULT 0;
ALTER TABLE product ADD COLUMN IF NOT EXISTS bus_error TEXT;
ALTER TABLE product ADD COLUMN IF NOT EXISTS bus_anunciado_at TIMESTAMP WITH TIME ZONE;

--changeset nexadrop:v165-indice-de-barrido-del-bus
--comment El barrido pregunta cada pocos segundos por los pendientes. Parcial: la inmensa mayoria de
--los productos tiene la columna nula y no hace falta indexarlos.

CREATE INDEX IF NOT EXISTS idx_product_bus_estado
    ON product (bus_estado) WHERE bus_estado IS NOT NULL;
