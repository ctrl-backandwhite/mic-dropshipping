--liquibase formatted sql

--changeset nexadrop:v167-titular-llc-en-los-documentos runOnChange:true
--comment Los documentos legales que LEE EL PUBLICO seguian nombrando al titular anterior.
--
--El escaparate pide los textos a la API y solo usa el fichero compilado del front como respaldo cuando
--el backend no responde. Es decir: manda la base. Y en la base seguian sembrados los textos de la
--persona fisica —«Jesus Enrique Finol Finol, NIF PENDIENTE-NIF, Calle Castelvi 7, 1D, 50004 Zaragoza,
--España»— mientras el fichero compilado ya decia «NX036 LLC, Sheridan, Wyoming». Los dos iban
--etiquetados con la MISMA version, 2026-08-15, asi que nada delataba la diferencia.
--
--Como pasó: la semilla de la v133 y el cambio de titular del front se hicieron el mismo dia. La semilla
--usa ON CONFLICT DO NOTHING —correcto para no pisar lo que edite el admin—, de modo que volver a
--ejecutarla nunca actualizo nada.
--
--Esto corrige QUIEN figura como responsable, su identificador fiscal y su domicilio. Se hace por
--sustitucion de texto y no reescribiendo los documentos para no tocar ni una coma de lo demas: lo que
--cambia es la identidad, no lo que se promete.
--
--PENDIENTE, y no se puede resolver desde aqui: el identificador fiscal sigue siendo un marcador porque
--el EIN de la sociedad todavia no ha llegado. Una factura sin identificador del emisor no es valida.
--
--`runOnChange` a proposito: si mañana cambia un dato identificativo, se edita este fichero y Liquibase
--lo vuelve a aplicar, en vez de acumular una migracion por cada correccion.

UPDATE legal_document
SET body = replace(
             replace(
               replace(body::text,
                 'Jesús Enrique Finol Finol', 'NX036 LLC'),
               'Calle Castelví 7, 1D, 50004 Zaragoza, España',
               '30 N Gould St, Ste R, Sheridan, WY 82801, Estados Unidos'),
             'PENDIENTE-NIF', 'PENDIENTE-EIN')::jsonb,
    updated_at = now(),
    updated_by = 'v167-titular-llc'
WHERE body::text LIKE '%Jesús Enrique Finol Finol%'
   OR body::text LIKE '%Calle Castelví 7%'
   OR body::text LIKE '%PENDIENTE-NIF%';

-- Y la ETIQUETA del identificador: una sociedad estadounidense tiene EIN, no NIF. Va aparte porque en
-- algún entorno ya se había corregido el titular dejando la etiqueta antigua —«con NIF PENDIENTE-EIN»—,
-- que es una mezcla de los dos sistemas y no identifica a nadie.
UPDATE legal_document
SET body = replace(replace(replace(replace(replace(replace(replace(body::text,
             'con NIF PENDIENTE-EIN', 'con EIN PENDIENTE-EIN'),
             'NIF PENDIENTE-EIN', 'EIN PENDIENTE-EIN'),
             'tax ID PENDIENTE-EIN', 'EIN PENDIENTE-EIN'),
             'codice fiscale PENDIENTE-EIN', 'EIN PENDIENTE-EIN'),
             'Steuernummer PENDIENTE-EIN', 'EIN PENDIENTE-EIN'),
             'fiscaal nummer PENDIENTE-EIN', 'EIN PENDIENTE-EIN'),
             '税号 PENDIENTE-EIN', 'EIN PENDIENTE-EIN')::jsonb,
    updated_at = now(),
    updated_by = 'v167-titular-llc'
WHERE body::text LIKE '%PENDIENTE-EIN%';

-- El mismo cambio sobre los BORRADORES sin publicar, si los hubiera: de lo contrario, la próxima vez que
-- alguien pulse Publicar en el panel volveria a colgar el titular antiguo.
UPDATE legal_document
SET draft_body = replace(
                   replace(
                     replace(draft_body::text,
                       'Jesús Enrique Finol Finol', 'NX036 LLC'),
                     'Calle Castelví 7, 1D, 50004 Zaragoza, España',
                     '30 N Gould St, Ste R, Sheridan, WY 82801, Estados Unidos'),
                   'PENDIENTE-NIF', 'PENDIENTE-EIN')::jsonb
WHERE draft_body IS NOT NULL
  AND (draft_body::text LIKE '%Jesús Enrique Finol Finol%'
    OR draft_body::text LIKE '%Calle Castelví 7%'
    OR draft_body::text LIKE '%PENDIENTE-NIF%');
