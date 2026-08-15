--liquibase formatted sql

--changeset nexadrop:v130-cobertura-real-yunexpress splitStatements:false
--
-- La tienda anunciaba 86 destinos y ninguno estaba verificado.
--
-- De dónde salían: la v85 montó la cobertura de YunExpress reutilizando `cainiao_shipping_zone` —la tabla
-- del transportista ANTERIOR— y quitándole Asia y África. Era una estimación provisional, con tarifas que
-- su propio comentario llama «mock hasta el rate card real de YunExpress», que nunca llegó. Nadie
-- comprobó nunca si YunExpress entrega de verdad en esos 86 países; simplemente se heredó la lista de otro
-- transportista.
--
-- Qué se hizo para contrastarlo. Se preguntó a la API de YunExpress con las credenciales del contrato: el
-- token se obtiene, pero `/v1/basic-data/products/getlist` y `/v1/price-trial/get` responden 500 en el
-- entorno de pruebas, así que no sirven como fuente. En su defecto se usó la red publicada por el propio
-- transportista: sede europea en los Países Bajos desde 2020, filiales en 21 países europeos y operación
-- en más de 30, tres filiales y siete centros en América del Norte, y destinos citados uno a uno en sus
-- líneas dedicadas.
--
-- Criterio: se queda el destino con entrega documentada por el transportista; se retira el que solo estaba
-- porque lo cubría Cainiao. Anunciar una bandera es prometer una entrega, y el pedido que no se puede
-- cursar se descubre DESPUÉS de cobrar.
--
-- Se desactiva, no se borra: la fila conserva su tarifa y su plazo, así que reactivar un destino cuando
-- llegue el rate card es poner `enabled = true`. Un DELETE dejaría además sin referencia a los pedidos
-- históricos de ese país.
--
-- Impacto comprobado antes de aplicar: ningún pedido a estos destinos, y un único usuario (Perú) que es de
-- la semilla de demostración.
--
-- QUEDAN 45: la Unión Europea completa, el Espacio Económico Europeo, Reino Unido y Suiza, América del
-- Norte, los cuatro grandes de Latinoamérica, Emiratos, Arabia Saudí e Israel, Australia y Nueva Zelanda.
-- Puerto Rico se mantiene porque su última milla es USPS, que el transportista documenta expresamente.
--
UPDATE cainiao_shipping_zone
   SET enabled = false
 WHERE country_code IN (
        -- Balcanes y Europa oriental no comunitarios: fuera de las filiales europeas del transportista.
        -- Ucrania y Moldavia, además, con la logística alterada por el conflicto.
        'AL','BA','ME','MK','RS','XK','MD','UA',
        -- Microestados sin operación propia: su correo depende del país vecino y no hay entrega directa.
        'AD','MC','SM',
        -- Latinoamérica dispersa. El transportista solo nombra Brasil, México, Argentina, Chile y
        -- Colombia; el resto son mercados sin red publicada, varios de ellos islas con aduana propia.
        'BB','BO','BS','BZ','CR','DO','EC','GT','GY','HN','HT','JM','NI','PA','PE','PY','SR','SV','TT',
        'UY','VE',
        -- Oriente Medio fuera de los tres destinos con operación declarada (AE, SA, IL).
        'BH','JO','KW','LB','OM','QA','TR',
        -- Islas del Pacífico sin red: plazos y costes que ninguna tarifa de la tabla refleja.
        'FJ','PG'
       );

-- Verificación dentro de la propia migración: si el recuento no cuadra, algo cambió la lista y hay que
-- mirarlo antes de que la tienda vuelva a anunciar destinos sin respaldo.
DO $$
DECLARE
    activos integer;
BEGIN
    SELECT count(*) INTO activos FROM cainiao_shipping_zone WHERE enabled;
    IF activos <> 45 THEN
        RAISE EXCEPTION 'Cobertura inesperada: % destinos activos, se esperaban 45', activos;
    END IF;
END $$;
