--liquibase formatted sql

-- Completa el operador económico de la UE con el código postal que faltaba, corrige el nombre de la calle
-- y PUBLICA el bloque.
--
-- Va en un changeset nuevo y no editando el de v121 porque aquél ya se ejecutó en los entornos: Liquibase
-- compara la suma de comprobación y modificarlo a posteriori rompería el arranque en cualquier sitio donde
-- ya estuviera aplicado.
--
-- Con la dirección completa se cumple el art. 16.3 del Reglamento (UE) 2023/988 —nombre, dirección postal
-- y correo electrónico— y el bloque puede pasar a `enabled = true`: hasta ahora la ficha y la factura lo
-- omitían a propósito, porque publicar unos datos de contacto incompletos no cumple la obligación, solo
-- aparenta cumplirla.
--
-- La calle es "Castelví" (v121 la sembró como "Catelvi", pendiente de confirmar por el titular). Se corrige
-- aquí y no allí por el mismo motivo de la suma de comprobación. Es un dato que se publica de cara al
-- comprador y que las autoridades de vigilancia usan para contactar: escrito mal, no localiza a nadie.

--changeset nexa:v123-responsible-person-postal
UPDATE eu_responsible_person
   SET postal_code  = '50004',
       address_line = 'Calle Castelví 7, 1D',
       enabled      = true,
       updated_at   = now(),
       updated_by   = 'v123-migration'
 WHERE id = 1;
