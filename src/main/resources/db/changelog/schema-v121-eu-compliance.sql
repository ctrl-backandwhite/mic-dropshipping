--liquibase formatted sql

-- Cumplimiento del Reglamento (UE) 2023/988 de seguridad general de los productos, aplicable desde el
-- 13-dic-2024, y del Reglamento (UE) 2019/1020 al que aquel remite.
--
-- Art. 16.1: un producto no puede introducirse en el mercado si no hay un operador económico establecido
-- en la Unión responsable de las tareas del art. 4.3 del 2019/1020. El ámbito del 2023/988 es TODO producto
-- de consumo salvo medicamentos, alimentos, piensos y seres vivos (art. 2.2), así que alcanza a la totalidad
-- del catálogo — no solo a los ~336 productos eléctricos, gafas de sol y relojes que además caen bajo la
-- lista cerrada del art. 4.5 del 2019/1020.
--
-- Art. 16.3: el nombre, el nombre comercial registrado o la marca registrada y los datos de contacto,
-- incluida la dirección postal Y DE CORREO ELECTRÓNICO, deben figurar "en el producto o en su envase, en el
-- paquete o en un documento de acompañamiento". Como el embalaje sale del proveedor y no se controla, la vía
-- practicable es el documento de acompañamiento: la factura.
--
-- Art. 19: en venta a distancia la OFERTA debe mostrar además el fabricante (nombre, dirección postal y
-- correo), la persona responsable, identificación del producto y las advertencias de seguridad.

--changeset nexa:v121-eu-responsible-person
-- Fila única (id = 1), mismo patrón que moq_margin_setting: no es una lista, es un ajuste del sistema.
CREATE TABLE IF NOT EXISTS eu_responsible_person (
    id           smallint PRIMARY KEY,
    enabled      boolean NOT NULL DEFAULT false,
    name         varchar(200) NOT NULL,
    address_line varchar(300) NOT NULL,
    postal_code  varchar(20),
    city         varchar(120) NOT NULL,
    region       varchar(120),
    country      varchar(2) NOT NULL,
    email        varchar(200) NOT NULL,
    phone        varchar(40),
    -- Cuál de las cuatro figuras del art. 4.2 del 2019/1020 es: MANUFACTURER, IMPORTER,
    -- AUTHORISED_REPRESENTATIVE o FULFILMENT_SERVICE_PROVIDER. Importa porque las obligaciones y la
    -- responsabilidad sobre producto difieren entre ellas.
    role         varchar(40) NOT NULL DEFAULT 'IMPORTER',
    updated_at   timestamptz NOT NULL DEFAULT now(),
    updated_by   varchar(120)
);

--changeset nexa:v121-product-manufacturer
-- Art. 19.a: la oferta en línea debe indicar nombre, dirección postal y correo del FABRICANTE. La columna
-- `brand` que ya existía no sirve: es una marca comercial, no una identidad contactable, y además está
-- vacía en 5.594 de 5.646 productos porque 1688 no la entrega en la carga.
ALTER TABLE product ADD COLUMN IF NOT EXISTS manufacturer_name    varchar(200);
ALTER TABLE product ADD COLUMN IF NOT EXISTS manufacturer_address varchar(300);
ALTER TABLE product ADD COLUMN IF NOT EXISTS manufacturer_email   varchar(200);

-- Índice parcial: el panel de admin necesita listar y contar rápido los que están incompletos, que hoy son
-- casi todos. Solo indexa las filas que faltan, así no crece con el catálogo ya corregido.
CREATE INDEX IF NOT EXISTS idx_product_sin_fabricante ON product (id)
    WHERE manufacturer_name IS NULL OR btrim(manufacturer_name) = '';

--changeset nexa:v121-category-safety-warning
-- Art. 19.d: advertencias de seguridad en la oferta, "en un lenguaje fácilmente comprensible para los
-- consumidores". Se cuelgan de la categoría y se heredan hacia los hijos, porque el riesgo es de la familia
-- de producto (baterías, piezas pequeñas, elementos eléctricos), no de la referencia concreta.
CREATE TABLE IF NOT EXISTS category_safety_warning (
    id          uuid PRIMARY KEY,
    category_id uuid NOT NULL REFERENCES category (id) ON DELETE CASCADE,
    -- Código estable del tipo de advertencia (p. ej. CHOKING_HAZARD). Permite reutilizar el mismo texto
    -- traducido en varias categorías y sobrevivir a un cambio de redacción.
    code        varchar(60) NOT NULL,
    position    integer NOT NULL DEFAULT 0,
    active      boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    created_by  varchar(120),
    updated_by  varchar(120),
    CONSTRAINT uk_category_safety_warning UNIQUE (category_id, code)
);

CREATE INDEX IF NOT EXISTS idx_safety_warning_category ON category_safety_warning (category_id)
    WHERE active;

CREATE TABLE IF NOT EXISTS category_safety_warning_translation (
    id         uuid PRIMARY KEY,
    warning_id uuid NOT NULL REFERENCES category_safety_warning (id) ON DELETE CASCADE,
    language   varchar(8) NOT NULL,
    text       varchar(1000) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_safety_warning_translation UNIQUE (warning_id, language)
);

--changeset nexa:v121-responsible-person-seed
-- Datos del operador económico, facilitados por el titular del negocio. Va en la migración y no en un
-- formulario vacío porque la obligación es de cumplimiento inmediato en todos los entornos: si la tabla
-- queda a cero, el escaparate incumple el art. 16.3 desde el primer despliegue.
--
-- `postal_code` queda a NULL A PROPÓSITO: el titular no lo facilitó y NO se inventa — una dirección postal
-- incompleta no cumple el requisito de "datos de contacto" del art. 16.3. El panel de admin lo marca como
-- pendiente y el bloque público no se publica hasta que `enabled` esté a true.
INSERT INTO eu_responsible_person (id, enabled, name, address_line, postal_code, city, region, country,
                                   email, role, updated_at, updated_by)
VALUES (1, false, 'Jesus Enrique Finol Finol', 'Calle Catelvi 7, 1D', NULL, 'Zaragoza', 'Zaragoza', 'ES',
        'jfinol02@gmail.com', 'IMPORTER', now(), 'v121-migration')
ON CONFLICT (id) DO NOTHING;
