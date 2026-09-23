package com.nexaplatform.dropshipping.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Recalcula la puntuación de tendencia de cada producto a partir de las VENTAS REALES de la tienda.
 *
 * <p><b>Por qué existe.</b> El escaparate ordena «Tendencia ahora» por {@code trend_score}, y ese campo no
 * lo escribía nadie: el único que lo tocaba era el sembrador de datos de demostración, apagado desde que el
 * catálogo pasó a datos reales. Resultado medido: 5.220 de 5.524 productos activos con puntuación CERO, y
 * la sección mostrando siempre los mismos 81 productos —los cargados entre el 4 y el 9 de julio, los únicos
 * con datos de cuando el sembrador aún corría—. Los 5.400 productos posteriores no es que no llegaran a
 * bestseller: es que competían contra un cero y no podían llegar por definición.
 *
 * <p><b>Qué cuenta como venta.</b> Unidades entregadas o en curso en los últimos 30 días, excluyendo lo
 * cancelado y lo reembolsado. Un pedido reembolsado no es una venta: contarlo premiaría justo al producto
 * que el comprador devolvió.
 *
 * <p><b>Por qué no se usan las ventas de 1688.</b> Sería lo fácil —llenaría la sección desde el primer día
 * con 5.400 productos— pero «bestseller» pasaría a significar «se vende mucho en China», no en esta tienda.
 * Con ventas propias la sección arranca pobre y se va llenando sola, y lo que dice es verdad. El campo
 * {@code monthly_sales} con el dato del proveedor se conserva intacto: sigue siendo información útil de
 * catálogo, pero ya no decide qué es tendencia.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrendScoreService {

    /** Ventana de ventas que se mira. 30 días es lo que la gente entiende por «ahora». */
    private static final int DIAS = 30;

    /**
     * Unidades a partir de las cuales un producto satura la señal de ventas. Deliberadamente bajo: con un
     * catálogo que arranca, exigir cientos de unidades dejaría a todo el mundo pegado al cero y volveríamos
     * al problema de partida. Sube cuando el volumen lo pida.
     */
    private static final int SATURACION = 25;

    private final JdbcTemplate jdbcTemplate;
    private final CatalogReindexRunner reindexRunner;

    @Value("${nexadrop.trend.recompute-enabled:true}")
    private boolean habilitado;

    /**
     * ¿Se reindexa después de recalcular? Sí en cualquier entorno real. En los tests se apaga: el barrido
     * corre en segundo plano y choca con el TRUNCATE con el que los casos limpian las tablas entre pruebas
     * — la puntuación acaba comprobándose igual, contra la base, que es donde se escribe.
     */
    @Value("${nexadrop.trend.reindex-after-recompute:true}")
    private boolean reindexarDespues;

    /**
     * Recalcula la puntuación de todo el catálogo activo.
     *
     * <p>Se hace en UNA sentencia y no producto a producto: con 5.500 productos, un bucle en Java serían
     * 5.500 lecturas más 5.500 escrituras y varios minutos de transacción abierta.
     *
     * <p>La fórmula pondera lo vendido (70%) y lo valorado (30%). Las ventas mandan —es lo que la sección
     * dice medir— pero la valoración evita que un único pedido afortunado desbanque a un producto con
     * historial. Los productos sin ventas quedan en cero y no aparecen en la sección: es correcto, no son
     * tendencia.
     *
     * <p><b>Sin {@code @Transactional} a propósito.</b> Es UNA sentencia: PostgreSQL ya la ejecuta de forma
     * atómica, y envolverla solo alargaba el tiempo que los bloqueos de fila del catálogo entero quedan
     * tomados —lo suficiente para provocar interbloqueos con cualquier otra escritura que llegue a la vez—.
     * El recuento posterior es solo informativo y no necesita ver la misma instantánea.
     *
     * @return cuántos productos han quedado con puntuación mayor que cero, es decir, con ventas reales.
     */
    public int recompute() {
        if (!habilitado) {
            return 0;
        }
        jdbcTemplate.update("""
                WITH ventas AS (
                    SELECT i.product_id, sum(i.quantity) AS unidades
                      FROM order_item i
                      JOIN customer_order o ON o.id = i.order_id
                     WHERE o.status NOT IN ('CANCELLED', 'REFUNDED')
                       AND o.created_at >= now() - (? || ' days')::interval
                     GROUP BY i.product_id
                )
                UPDATE product p
                   SET trend_score = round((
                           0.7 * least(1.0, coalesce(v.unidades, 0)::numeric / ?)
                         + 0.3 * (coalesce(p.rating, 0)::numeric / 5)
                       )::numeric, 4),
                       updated_at = now()
                  FROM (SELECT id FROM product WHERE status = 'ACTIVE') AS act
                  LEFT JOIN ventas v ON v.product_id = act.id
                 WHERE p.id = act.id
                """, DIAS, SATURACION);

        Integer conVentas = jdbcTemplate.queryForObject("""
                SELECT count(DISTINCT i.product_id)
                  FROM order_item i
                  JOIN customer_order o ON o.id = i.order_id
                 WHERE o.status NOT IN ('CANCELLED', 'REFUNDED')
                   AND o.created_at >= now() - (? || ' days')::interval
                """, Integer.class, DIAS);
        int n = conVentas == null ? 0 : conVentas;
        log.info("::> [TREND] puntuación recalculada sobre ventas reales de {} días — {} productos con ventas", DIAS,
                n);
        // El índice de búsqueda guarda su propia copia de la puntuación y la usa para desempatar los
        // resultados. Sin reindexar, la base y el índice divergen desde la primera pasada: medido, un
        // producto con 0,9898 en base seguía valiendo 0,794 para el buscador. Va en segundo plano y
        // respeta el cerrojo del reindexado manual, así que no se solapa ni bloquea el barrido nocturno.
        if (reindexarDespues && reindexRunner.tryAcquire()) {
            reindexRunner.runAsync();
        } else {
            log.info("::> [TREND] ya hay un reindexado en curso; el índice recogerá la puntuación nueva");
        }
        return n;
    }

    /**
     * Recálculo diario de madrugada. La tendencia cambia despacio; hacerlo más a menudo solo añadiría
     * escrituras sobre todo el catálogo sin que nadie note la diferencia.
     */
    @Scheduled(cron = "${nexadrop.trend.recompute-cron:0 20 3 * * *}")
    public void recomputeDiario() {
        recompute();
    }
}
