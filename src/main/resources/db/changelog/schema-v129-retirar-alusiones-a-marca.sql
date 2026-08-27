--liquibase formatted sql

-- Retira 20 productos cuyo título alude a una marca registrada.
--
-- No es una cuestión de estilo. Los títulos llevaban una coletilla —«(inspiración no oficial)»— que es una
-- ADMISIÓN POR ESCRITO de que el artículo imita un diseño ajeno, y el diseño aludido se reconoce sin
-- esfuerzo:
--
--   estilo H         → Hermès          suela roja  → Louboutin
--   estilo SB        → Nike SB         trébol / 三叶子 → Adidas
--   Retro 530        → New Balance     estilo GM   → Gentle Monster
--   cámara de aire   → Nike Air
--
-- El §0.A.1 del playbook de carga lo prohíbe expresamente, incluidos los «sin logo que copian un diseño
-- registrado». Y quitar la coletilla no arregla nada: si la sandalia ES una copia del diseño de Hermès,
-- lo sigue siendo sin la etiqueta. Lo que está en juego no es un paquete retenido: la mercancía se
-- DESTRUYE en aduana de la UE, responde el vendedor —no el proveedor de 1688— ante el titular de la
-- marca, y YunExpress cierra la cuenta. Sin transportista no se despacha ni lo ya vendido.
--
-- Ninguno tenía pedidos ni favoritos. La lista completa con external_id queda en
-- backend/docs/catalogo/ELIMINADOS_POR_USUARIO_2026-08-14.tsv — NO se recargan.
--
-- Prevención: `valida_permitido.py` ya bloqueaba marcas explícitas (nike, adidas…), pero NO estas
-- alusiones indirectas. Se añade el patrón allí y la regla queda escrita en las dos skills de carga.

--changeset nexa:v129-retirar-alusiones-a-marca splitStatements:false
DO $marcas$
DECLARE
    v_con_pedidos int;
    v_borradas    int;
BEGIN
    CREATE TEMP TABLE IF NOT EXISTS marcas_v129 AS
    SELECT DISTINCT p.id
      FROM product p
      JOIN product_translation t ON t.product_id = p.id
     WHERE t.title ~* '(unofficial|no oficial|non officiel|inoffiziell|non ufficiale|niet-officieel|não oficial)'
        OR t.title ~ '(三叶子|三叶草)'
        -- Segunda pasada: la coletilla no siempre dice «no oficial». Estos aparecieron después, con
        -- «(inspiración)» a secas, «estilo GM» (Gentle Monster) o —el peor— «con logo 'B' (inspiración
        -- Balmain)», que ya no alude: nombra la marca y admite que lleva su logo.
        OR t.title ~* '\(inspir'
        OR t.title ~* 'inspiraci[oó]n [A-Z]'
        OR t.title ~* 'estilo GM'
        OR t.title ~* 'con logo';

    SELECT count(*) INTO v_con_pedidos
      FROM order_item oi WHERE oi.product_id IN (SELECT id FROM marcas_v129);
    IF v_con_pedidos > 0 THEN
        RAISE EXCEPTION 'v129 abortada: % líneas de pedido referencian productos con alusión a marca. '
                        'Revisar a mano: una venta real no puede perder su producto', v_con_pedidos;
    END IF;

    DELETE FROM category_ranking WHERE product_id IN (SELECT id FROM marcas_v129);
    DELETE FROM product WHERE id IN (SELECT id FROM marcas_v129);
    GET DIAGNOSTICS v_borradas = ROW_COUNT;
    RAISE NOTICE 'v129: % productos con alusión a marca retirados', v_borradas;

    DROP TABLE IF EXISTS marcas_v129;
END
$marcas$;
