--liquibase formatted sql

--changeset nexadrop:v134-borrador-legal
--
-- Separa el BORRADOR del texto PUBLICADO.
--
-- Sin esta separación, guardar desde el admin cambiaba el texto en vivo: quien estuviera redactando una
-- nueva redacción de la privacidad la iría publicando frase a frase, y cualquiera que entrara mientras
-- tanto leería un documento a medio escribir. En un texto legal eso no es un detalle estético — es la
-- versión que vincula al usuario, y tiene que estar completa cuando se hace visible.
--
-- Ahora hay dos columnas: `body` es lo que el escaparate sirve y `draft_body` lo que el admin edita.
-- Guardar toca solo el borrador; publicar lo copia sobre el publicado, sube la versión y avisa.
--
ALTER TABLE legal_document
    ADD COLUMN IF NOT EXISTS draft_title varchar(200),
    ADD COLUMN IF NOT EXISTS draft_body  jsonb;

COMMENT ON COLUMN legal_document.draft_body IS
    'Borrador en edición. NULL = no hay cambios pendientes; el escaparate nunca lo sirve.';

-- Los documentos existentes arrancan sin borrador: lo que hay publicado es lo bueno.
UPDATE legal_document SET draft_title = NULL, draft_body = NULL;
