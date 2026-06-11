--liquibase formatted sql
--changeset nexadrop:v16-category-pt-translations
-- DROP-465: hasta esta versión el seed copiaba la traducción ES dentro del campo PT,
-- así que al abrir el formulario de edición en /admin/categories el campo "Portugués"
-- aparecía pre-rellenado con texto en español. Corregimos las traducciones PT para
-- las 14 categorías raíz que se crean en el seed.
UPDATE category_translation SET name = 'Eletrônica de consumo'      WHERE language = 'pt' AND category_id = (SELECT id FROM category WHERE slug = 'consumer-electronics');
UPDATE category_translation SET name = 'Moda e vestuário'           WHERE language = 'pt' AND category_id = (SELECT id FROM category WHERE slug = 'fashion-apparel');
UPDATE category_translation SET name = 'Casa e cozinha'             WHERE language = 'pt' AND category_id = (SELECT id FROM category WHERE slug = 'home-kitchen');
UPDATE category_translation SET name = 'Beleza e cuidado pessoal'   WHERE language = 'pt' AND category_id = (SELECT id FROM category WHERE slug = 'beauty-personal-care');
UPDATE category_translation SET name = 'Esportes e ar livre'        WHERE language = 'pt' AND category_id = (SELECT id FROM category WHERE slug = 'sports-outdoors');
UPDATE category_translation SET name = 'Brinquedos e presentes'     WHERE language = 'pt' AND category_id = (SELECT id FROM category WHERE slug = 'toys-gifts');
UPDATE category_translation SET name = 'Auto e motos'               WHERE language = 'pt' AND category_id = (SELECT id FROM category WHERE slug = 'auto-parts');
UPDATE category_translation SET name = 'Escritório e papelaria'     WHERE language = 'pt' AND category_id = (SELECT id FROM category WHERE slug = 'office-supplies');
UPDATE category_translation SET name = 'Animais de estimação'       WHERE language = 'pt' AND category_id = (SELECT id FROM category WHERE slug = 'pet-supplies');
UPDATE category_translation SET name = 'Ferramentas'                WHERE language = 'pt' AND category_id = (SELECT id FROM category WHERE slug = 'tools-hardware');
UPDATE category_translation SET name = 'Joias e relógios'           WHERE language = 'pt' AND category_id = (SELECT id FROM category WHERE slug = 'jewelry-watches');
UPDATE category_translation SET name = 'Jardim e exterior'          WHERE language = 'pt' AND category_id = (SELECT id FROM category WHERE slug = 'garden-outdoor');
UPDATE category_translation SET name = 'Bebês e maternidade'        WHERE language = 'pt' AND category_id = (SELECT id FROM category WHERE slug = 'baby-maternity');
UPDATE category_translation SET name = 'Iluminação'                 WHERE language = 'pt' AND category_id = (SELECT id FROM category WHERE slug = 'lighting');
