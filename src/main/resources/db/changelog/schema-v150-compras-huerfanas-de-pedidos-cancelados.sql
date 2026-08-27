--liquibase formatted sql

--changeset nexadrop:v150-compras-huerfanas-de-pedidos-cancelados splitStatements:false
--
-- Retira del tablero de compras lo que quedó colgando de pedidos cancelados o reembolsados.
--
-- El tablero (`SupplierPurchaseService.openQueue`) se pinta por el estado de la COMPRA y nunca mira el
-- del PEDIDO. Cancelar reembolsaba, devolvía el stock y ponía el pedido en CANCELLED, pero no tocaba
-- `supplier_purchase`: la compra se quedaba en PENDING y su tarjeta seguía en «POR COMPRAR». El admin
-- acababa comprando en 1688 género de una venta que ya no existe, y ese dinero no se le reclama a nadie.
--
-- El código ya no las deja huérfanas (`cancelUnbought`, llamado desde cancelMyOrder / cancelOrder /
-- refundOrder), pero las que se crearon antes siguen ahí. Esto las limpia.
--
-- SOLO se tocan las PENDING, que es el único estado en el que no ha salido dinero. Una compra ya pagada
-- al proveedor se queda en el tablero aunque su pedido esté cancelado: esa mercancía existe, va camino
-- del almacén chino y allí SE DESTRUYE SIN COMPENSACIÓN a los 30 días si nadie da instrucciones.
-- Esconderla sería perder el rastro de un bulto real, que es un problema peor que el que se arregla.
--
-- OJO: `status` es varchar y guarda el NOMBRE del enum, no el `progress()` numérico. Compararlo con 0
-- o con -1 no da error: no casa con nada y la migración se aplicaría sin tocar una sola fila.
UPDATE supplier_purchase sp
SET status = 'CANCELLED',
    notes = COALESCE(sp.notes, 'Retirada: el pedido fue cancelado o reembolsado'),
    updated_at = now()
FROM customer_order co
WHERE co.id = sp.order_id
  AND sp.status = 'PENDING'
  AND co.status IN ('CANCELLED', 'REFUNDED');
