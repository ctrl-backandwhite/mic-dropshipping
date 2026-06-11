--liquibase formatted sql
--changeset nexadrop:v25-mentor-bios-es
-- DROP-510: los bios y headlines de mentores estaban en inglés porque el seed
-- los crea así. Reescribimos a español ya que la UI principal del producto es
-- en ES; cuando montemos un pipeline de traducción real podremos generar
-- variantes por idioma.

UPDATE mentor_profile SET
  headline = 'Especialista en sourcing China-Europa',
  bio = 'Diez años conectando vendedores europeos con fábricas verificadas de Shenzhen y Yiwu. Ayudo a evitar errores típicos de MOQ, control de calidad e Incoterms.'
WHERE headline ILIKE '%sourcing%' OR headline ILIKE '%china%';

UPDATE mentor_profile SET
  headline = 'Experta en branding y storytelling DTC',
  bio = 'Doce años en marketing de marcas DTC con foco en Shopify y TikTok. Diseño narrativas de marca y guías visuales que mueven la conversión sin parecer agresivas.'
WHERE headline ILIKE '%brand%' OR headline ILIKE '%marketing%';

UPDATE mentor_profile SET
  headline = 'Especialista en TikTok Ads y UGC',
  bio = 'Construyo creatividades UGC y campañas TikTok que escalan a 6 cifras mensuales. Ofrezco auditoría de cuenta, hooks y un sistema de testeo creativo.'
WHERE headline ILIKE '%tiktok%' OR headline ILIKE '%ads%' OR headline ILIKE '%ugc%';

UPDATE mentor_profile SET
  headline = 'Logística internacional y fulfilment',
  bio = 'Quince años diseñando cadenas logísticas desde China a EU/US. Aire vs. mar, FBA, despacho, devoluciones — te ayudo a elegir el setup óptimo para tu margen.'
WHERE headline ILIKE '%logist%' OR headline ILIKE '%fulfil%' OR headline ILIKE '%shipping%';

UPDATE mentor_profile SET
  headline = 'Fotografía de producto y catálogo',
  bio = 'Fotógrafa de producto especializada en moda y lifestyle. Reviso tu catálogo y te indico cómo levantar el CTR de la ficha con mejor fotografía y mockups.'
WHERE headline ILIKE '%photo%' OR headline ILIKE '%foto%';

-- Mentor "fallback" sin keyword identificable: bio neutro pero en ES.
UPDATE mentor_profile SET
  bio = 'Mentor verificado de la red NX036. Reserva una sesión para revisar tu caso particular: sourcing, marketing, logística o estrategia de producto.'
WHERE bio IS NULL OR bio = ''
   OR bio ILIKE '%TODO%' OR bio ILIKE '%lorem%';

-- expertise: persistido como jsonb. Si está vacío, asignamos un set
-- normalizado que el diccionario del frontend mapea a ES/PT/ZH/FR/DE/IT/NL.
UPDATE mentor_profile
SET expertise = '["sourcing","branding","tiktok","logistics","marketing"]'::jsonb
WHERE expertise IS NULL OR jsonb_array_length(expertise) = 0;
