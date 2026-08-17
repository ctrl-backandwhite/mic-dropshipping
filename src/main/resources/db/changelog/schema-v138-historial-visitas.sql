--liquibase formatted sql

--changeset nexadrop:v138-001-product-view splitStatements:false
-- HISTORIAL DE PRODUCTOS VISITADOS. El usuario abre una ficha, se va, y al volver no sabe encontrarla otra
-- vez: recordaba el producto pero no cómo llegó a él. Esta tabla guarda por qué fichas ha pasado para
-- poder devolvérselas en su área («lo que has visto») y para recordárselas por correo cada tres días.
--
-- Solo se registra CON SESIÓN INICIADA: sin usuario no hay a quién asociar la visita ni a quién escribirle,
-- y rastrear a un visitante anónimo sería recoger un dato que no vamos a usar.
--
-- La identidad de una fila es (usuario, producto) y NO (usuario, producto, momento): una visita se
-- CONSOLIDA actualizando viewed_at en vez de insertar una fila nueva. Sin esa unicidad, alguien que deja la
-- ficha abierta y recarga treinta veces mete treinta filas idénticas, el historial se convierte en el mismo
-- producto repetido en pantalla y el correo de cada tres días enseña una cuadrícula de duplicados. view_count
-- conserva lo único que se perdía al consolidar —cuántas veces ha vuelto— que es justo la señal de interés.
--
-- Ambas claves ajenas van en CASCADE: al borrar la cuenta desaparece su historial (es dato personal, no
-- tiene por qué sobrevivir), y al retirar un producto del catálogo desaparecen las visitas que lo apuntaban,
-- que es lo que evita que el correo intente pintar una ficha que ya no existe.
CREATE TABLE IF NOT EXISTS product_view (
    id          uuid        PRIMARY KEY,
    user_id     uuid        NOT NULL,
    product_id  uuid        NOT NULL,
    viewed_at   timestamptz NOT NULL DEFAULT now(),
    view_count  integer     NOT NULL DEFAULT 1,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    created_by  varchar(120),
    updated_by  varchar(120),
    CONSTRAINT uq_product_view_user_product UNIQUE (user_id, product_id),
    CONSTRAINT fk_product_view_user    FOREIGN KEY (user_id)    REFERENCES users(id)   ON DELETE CASCADE,
    CONSTRAINT fk_product_view_product FOREIGN KEY (product_id) REFERENCES product(id) ON DELETE CASCADE
);

-- Las dos únicas lecturas del historial son «las fichas de ESTE usuario, de la más reciente a la más
-- antigua» (su página) y «las de ESTE usuario en los últimos tres días» (el correo). Las dos son el mismo
-- filtro y el mismo orden, así que un solo índice las resuelve sin ordenar en memoria.
CREATE INDEX IF NOT EXISTS idx_product_view_user_viewed ON product_view (user_id, viewed_at DESC);

-- El historial se conserva 90 días y luego se purga. La purga barre por fecha SIN usuario, de modo que el
-- índice de arriba no le sirve: sin este, cada pasada recorrería la tabla entera.
CREATE INDEX IF NOT EXISTS idx_product_view_viewed ON product_view (viewed_at);
