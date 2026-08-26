--liquibase formatted sql

--changeset nexadrop:v156-welcome-ejemplos
--comment Productos de ejemplo de la guia de bienvenida: fila unica, con eleccion manual opcional

-- La guia de bienvenida ensena las dos reglas que mas dinero le ahorran al comprador en la Union Europea:
-- el arancel se paga por PARTIDA declarada (no por unidad) y el envio se paga por BULTO (no por producto).
-- Hasta ahora lo hacia con numeros inventados -12 EUR de producto, 7 de envio-, y una regla contada con
-- cifras de mentira no convence a nadie. Pasa a ensenarse con productos REALES del catalogo, que ademas
-- el visitante puede sumar y restar para ver moverse el arancel y el porte.
--
-- La eleccion es automatica: hacen falta DOS productos que compartan partida y UNO de otra distinta, o la
-- leccion no se ve. Estas columnas permiten al admin fijarlos a mano cuando quiera destacar otros; a NULL,
-- manda la eleccion automatica.
CREATE TABLE IF NOT EXISTS welcome_example_setting (
  id          SMALLINT     PRIMARY KEY,
  product_id_1 UUID        REFERENCES product (id) ON DELETE SET NULL,
  product_id_2 UUID        REFERENCES product (id) ON DELETE SET NULL,
  product_id_3 UUID        REFERENCES product (id) ON DELETE SET NULL,
  updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_by  VARCHAR(120)
);

COMMENT ON TABLE welcome_example_setting IS
  'Fila unica (id=1) con los tres productos que ilustran la guia de bienvenida. A NULL, se eligen solos: dos que compartan partida arancelaria y uno de otra.';

INSERT INTO welcome_example_setting (id, updated_by)
VALUES (1, 'v156-welcome-ejemplos')
ON CONFLICT (id) DO NOTHING;

--rollback DROP TABLE IF EXISTS welcome_example_setting;
