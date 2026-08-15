package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.application.service.TrendScoreService;
import com.nexaplatform.dropshipping.config.BaseIntegration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La puntuación de tendencia, contra base de datos real.
 *
 * <p>El unitario comprueba que la consulta dice lo que debe decir; esto comprueba que además CALCULA lo
 * que debe. Son cosas distintas: la fórmula vive dentro de un SQL con ventanas, LEFT JOIN y saturación, y
 * ahí un paréntesis mal puesto no rompe nada — simplemente ordena mal el escaparate, que es exactamente el
 * fallo que estuvo meses sin detectarse.
 *
 * <p>Los casos borde son los que importan, porque todos tienen consecuencia visible: un pedido cancelado
 * que puntúe pone en «Tendencia ahora» un producto que nadie se quedó; una venta de hace un año que siga
 * contando congela la sección igual que estaba antes del arreglo; y un producto inactivo que se cuele
 * enseña al comprador algo que no puede comprar.
 */
class TrendScoreIT extends BaseIntegration {

    @Autowired
    private TrendScoreService trendScoreService;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID categoria;
    private UUID usuario;
    private UUID direccion;

    @BeforeEach
    void escenario() {
        categoria = UUID.randomUUID();
        jdbc.update("INSERT INTO category (id, slug, name_zh, active, created_at, updated_at)"
                + " VALUES (?, ?, ?, true, now(), now())", categoria, "cat-" + categoria, "分类");
        usuario = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, role, active, created_at, updated_at)"
                + " VALUES (?, ?, 'x', 'USER', true, now(), now())", usuario, "trend-" + usuario + "@test");
        direccion = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO address (id, full_name, line1, city, postal_code, country, created_at)
                VALUES (?, 'Cert', 'Calle 1', 'Zaragoza', '50004', 'ES', now())
                """, direccion);
    }

    /** Producto activo con la valoración dada. Devuelve su id. */
    private UUID producto(String nombre, BigDecimal rating, String estado) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO product (id, external_id, source, slug, title_zh, status, category_id,
                                     base_price, currency, rating, monthly_sales, trend_score,
                                     created_at, updated_at)
                VALUES (?, ?, 'test', ?, ?, ?, ?, 10, 'CNY', ?, 0, 0, now(), now())
                """, id, nombre + "-" + id, nombre + "-" + id, nombre, estado, categoria, rating);
        return id;
    }

    /** Pedido con una línea, en el estado y la antigüedad dados. */
    private void pedido(UUID productId, int unidades, String estado, int diasAtras) {
        UUID orden = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO customer_order (id, order_number, user_id, shipping_address_id, status,
                                            subtotal_cents, shipping_cents, tax_cents, total_cents,
                                            currency, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 100, 0, 0, 100, 'USD', now() - (? || ' days')::interval, now())
                """, orden, "T-" + orden.toString().substring(0, 8), usuario, direccion, estado, diasAtras);
        jdbc.update("""
                INSERT INTO order_item (id, order_id, product_id, quantity, unit_price_cents, cost_cents,
                                        line_total_cents)
                VALUES (?, ?, ?, ?, 100, 50, ?)
                """, UUID.randomUUID(), orden, productId, unidades, unidades * 100);
    }

    private BigDecimal scoreDe(UUID productId) {
        return jdbc.queryForObject("SELECT trend_score FROM product WHERE id = ?", BigDecimal.class, productId);
    }

    @Test
    @DisplayName("quien vende puntúa más que quien no vende, aunque el otro esté mejor valorado")
    void lasVentasPesanMasQueLaValoracion() {
        // Es la razón de ser del cambio: antes la sección la encabezaba quien tenía el dato de 1688, no
        // quien vendía. Un producto con cinco estrellas y CERO ventas no puede ir por delante de uno que
        // se está vendiendo.
        UUID vende = producto("vende", new BigDecimal("3.0"), "ACTIVE");
        UUID soloEstrellas = producto("estrellas", new BigDecimal("5.0"), "ACTIVE");
        pedido(vende, 20, "DELIVERED", 3);

        trendScoreService.recompute();

        assertThat(scoreDe(vende)).isGreaterThan(scoreDe(soloEstrellas));
    }

    @Test
    @DisplayName("un pedido CANCELADO no puntúa")
    void elCanceladoNoPuntua() {
        UUID p = producto("cancelado", BigDecimal.ZERO, "ACTIVE");
        pedido(p, 25, "CANCELLED", 2);

        trendScoreService.recompute();

        // Sin rating y sin ventas válidas, el score tiene que ser exactamente cero.
        assertThat(scoreDe(p)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("un pedido REEMBOLSADO no puntúa")
    void elReembolsadoNoPuntua() {
        // Contar un reembolso premiaría justo al producto que el comprador devolvió.
        UUID p = producto("reembolsado", BigDecimal.ZERO, "ACTIVE");
        pedido(p, 25, "REFUNDED", 2);

        trendScoreService.recompute();

        assertThat(scoreDe(p)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("una venta de hace más de 30 días ya no cuenta")
    void fueraDeLaVentanaNoCuenta() {
        // Sin esta caducidad, la sección se congelaría con los éxitos de siempre — el problema exacto
        // que se estaba arreglando.
        UUID p = producto("antiguo", BigDecimal.ZERO, "ACTIVE");
        pedido(p, 25, "DELIVERED", 40);

        trendScoreService.recompute();

        assertThat(scoreDe(p)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("justo dentro de la ventana sí cuenta")
    void enElBordeDeLaVentanaCuenta() {
        // Valor límite por el otro lado: 29 días entra. Si el corte estuviera mal planteado, las ventas
        // de la última semana del mes desaparecerían sin que nadie lo notara.
        UUID p = producto("borde", BigDecimal.ZERO, "ACTIVE");
        pedido(p, 10, "DELIVERED", 29);

        trendScoreService.recompute();

        assertThat(scoreDe(p)).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("a partir del tope de unidades la señal satura y no crece más")
    void laSenalSatura() {
        // Sin tope, un único pedido mayorista dejaría al resto del catálogo pegado al cero para siempre.
        UUID mucho = producto("mucho", BigDecimal.ZERO, "ACTIVE");
        UUID muchisimo = producto("muchisimo", BigDecimal.ZERO, "ACTIVE");
        pedido(mucho, 25, "DELIVERED", 1);
        pedido(muchisimo, 5000, "DELIVERED", 1);

        trendScoreService.recompute();

        assertThat(scoreDe(mucho)).isEqualByComparingTo(scoreDe(muchisimo));
    }

    @Test
    @DisplayName("varios pedidos del mismo producto suman unidades")
    void variosPedidosSuman() {
        UUID uno = producto("uno", BigDecimal.ZERO, "ACTIVE");
        UUID troceado = producto("troceado", BigDecimal.ZERO, "ACTIVE");
        pedido(uno, 10, "DELIVERED", 1);
        pedido(troceado, 4, "DELIVERED", 1);
        pedido(troceado, 6, "DELIVERED", 2);

        trendScoreService.recompute();

        // Diez unidades en un pedido o repartidas en dos: la demanda es la misma.
        assertThat(scoreDe(troceado)).isEqualByComparingTo(scoreDe(uno));
    }

    @Test
    @DisplayName("un producto sin ventas ni valoración se queda en cero y no aparece en la sección")
    void sinNadaSeQuedaEnCero() {
        UUID p = producto("vacio", BigDecimal.ZERO, "ACTIVE");

        trendScoreService.recompute();

        assertThat(scoreDe(p)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("un producto INACTIVO no se recalcula")
    void elInactivoNoSeToca() {
        // Se le deja el valor que tuviera: no compite en el escaparate y gastar escrituras en él solo
        // alarga la transacción sobre la tabla que sirve el catálogo.
        UUID p = producto("inactivo", new BigDecimal("5.0"), "DRAFT");
        jdbc.update("UPDATE product SET trend_score = 0.9999 WHERE id = ?", p);

        trendScoreService.recompute();

        assertThat(scoreDe(p)).isEqualByComparingTo("0.9999");
    }

    @Test
    @DisplayName("recalcular dos veces seguidas da el mismo resultado")
    void esIdempotente() {
        // El barrido corre cada noche sobre el catálogo entero: si cada pasada moviera los valores, el
        // orden del escaparate cambiaría solo, sin que nadie hubiera comprado nada.
        UUID p = producto("estable", new BigDecimal("4.0"), "ACTIVE");
        pedido(p, 7, "DELIVERED", 5);

        trendScoreService.recompute();
        BigDecimal primera = scoreDe(p);
        trendScoreService.recompute();

        assertThat(scoreDe(p)).isEqualByComparingTo(primera);
    }

    @Test
    @DisplayName("devuelve cuántos productos DISTINTOS tienen ventas, no cuántos pedidos hay")
    void cuentaProductosNoPedidos() {
        UUID a = producto("a", BigDecimal.ZERO, "ACTIVE");
        UUID b = producto("b", BigDecimal.ZERO, "ACTIVE");
        pedido(a, 1, "DELIVERED", 1);
        pedido(a, 1, "DELIVERED", 2);
        pedido(b, 1, "DELIVERED", 3);

        assertThat(trendScoreService.recompute()).isEqualTo(2);
    }
}
