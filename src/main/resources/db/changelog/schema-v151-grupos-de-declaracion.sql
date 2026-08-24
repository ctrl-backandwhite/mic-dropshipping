--liquibase formatted sql

--changeset nexadrop:v151-grupos-de-declaracion splitStatements:false
--
-- Agrupación arancelaria: una línea de declaración por terna, no por producto.
--
-- El derecho temporal de la UE son 3 EUR por LÍNEA de declaración y por bulto, y lo que separa una
-- línea de otra es la terna del art. 1(61) del Reglamento Delegado (UE) 2015/2446: clasificación,
-- descripción y origen. Hasta hoy cada producto viajaba con SU título en inglés como descripción, así
-- que dos productos distintos eran siempre dos líneas aunque compartieran partida.
--
-- Esta tabla guarda, por terna (partida + material + uso), la descripción genérica con la que se
-- declararán todos los productos que la compartan. Al compartir descripción, la aduana los cuenta como
-- UNA línea — que es el ejemplo oficial de la Comisión: anorak, cortavientos y cazadora bajo la
-- subpartida 6104 19 pagan 3 EUR, no 9.
--
-- `approved_at` NO es informativo: mientras esté a NULL el grupo NO agrupa y cada producto sigue siendo
-- su propia línea. Se cobra de más en el peor caso, nunca de menos. Cargar productos nuevos nunca puede
-- abaratar el arancel por accidente.
--
CREATE TABLE customs_declaration_group (
    id            uuid PRIMARY KEY,
    hs6           varchar(6)   NOT NULL,
    material      varchar(120) NOT NULL DEFAULT '',
    usage_code    varchar(120) NOT NULL DEFAULT '',
    ename         varchar(512) NOT NULL,
    cname         varchar(512) NOT NULL DEFAULT '',
    product_count integer      NOT NULL DEFAULT 0,
    approved_at   timestamptz,
    approved_by   varchar(120),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uk_customs_declaration_group_terna UNIQUE (hs6, material, usage_code)
);

CREATE INDEX idx_customs_declaration_group_aprobados
    ON customs_declaration_group (hs6, material, usage_code) WHERE approved_at IS NOT NULL;

-- Snapshot de la descripción con la que se declaró el pedido.
--
-- Sin esto la cadena se rompe por la mitad: la vista previa contaría UNA línea (con la descripción del
-- grupo) y el despacho contaría DOS (con el título del producto, que es de donde lo saca hoy
-- OrderUseCaseImpl). El cliente vería un importe y se le cobraría otro — exactamente el fallo que el
-- conteo por terna corrigió en su día.
--
-- Los pedidos anteriores la tienen a NULL y siguen resolviendo por `product_titles->>'en'`, que es
-- como se declararon de verdad. Un pedido cuenta por lo que se declaró, no por cómo se agrupe hoy.
ALTER TABLE order_item ADD COLUMN declared_description varchar(512);

-- Interruptor por país. El derecho de 3 EUR CADUCA por norma el 1-jul-2028 (Reglamento (UE) 2026/382),
-- así que esto no es permanente y tiene que poder apagarse sin desplegar. Apagarlo en un destino lo
-- devuelve a una línea por producto sin tocar los grupos ya aprobados, que siguen ahí para cuando se
-- vuelva a encender.
ALTER TABLE country_customs_rule
    ADD COLUMN group_declaration_lines boolean NOT NULL DEFAULT true;
