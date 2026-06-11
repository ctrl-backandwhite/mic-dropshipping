--liquibase formatted sql
--changeset nexadrop:v19-clean-placeholder-images
-- DROP-412: el QA reportaba que catálogo y PDP renderizaban cuadros pastel
-- con texto "800 × 800". Esto pasa cuando product_image.source_url apunta a
-- un servicio de placeholder (via.placeholder.com, placehold.co, picsum,
-- dummyimage, fakeimg, loremflickr). source_url es NOT NULL, así que en lugar
-- de NULL borramos directamente las filas que apuntaban al servicio.
-- SafeImage en el frontend renderiza el icono pastel neutro cuando un producto
-- llega sin imágenes.

DELETE FROM product_image
WHERE source_url ~* '(via\.placeholder|placehold\.co|placeimg|dummyimage|placekitten|loremflickr|fakeimg|picsum)';

-- Saneamos cdn_url placeholders dejándolo NULL (sí permite NULL en schema).
UPDATE product_image
SET cdn_url = NULL
WHERE cdn_url IS NOT NULL
  AND cdn_url ~* '(via\.placeholder|placehold\.co|placeimg|dummyimage|placekitten|loremflickr|fakeimg|picsum)';
