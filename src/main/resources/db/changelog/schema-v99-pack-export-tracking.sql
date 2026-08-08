--liquibase formatted sql

--changeset nexadrop:v99-pack-export-tracking splitStatements:false
-- Marca de exportación al fichero de re-empaquetado del OMS de Yunfulfillment.
--
-- El .xls se generaba SIEMPRE con todas las compras de la cola, así que re-descargarlo repetía las
-- mismas filas. El OMS rechaza un número YT que ya importó («YT already exists») y tumba el fichero
-- entero. Con esta marca, una compra ya volcada a un fichero descargado no vuelve a salir en el
-- siguiente, salvo que el admin la marque a mano para re-exportar (poniendo exported_at a NULL).
--
-- Nullable a propósito: NULL = pendiente de exportar (entra en el próximo fichero); con fecha = ya
-- exportada (fuera de la cola de exportación, pero sigue en la cola de compras/seguimiento). El
-- filtro de la vista NO usa esta columna: una compra exportada sigue viéndose y gestionándose.
ALTER TABLE supplier_purchase ADD COLUMN IF NOT EXISTS exported_at timestamptz;

-- La cola de exportación filtra por (status abierto AND exported_at IS NULL): índice parcial para que
-- esa consulta no recorra las miles de compras ya exportadas.
CREATE INDEX IF NOT EXISTS idx_supplier_purchase_pending_export
    ON supplier_purchase (created_at)
    WHERE exported_at IS NULL;
