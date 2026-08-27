package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.application.service.TrendScoreService;
import com.nexaplatform.dropshipping.config.TestContainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

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
@SpringBootTest
@ActiveProfiles("test")
@Import(TestContainersConfiguration.class)
class TrendScoreIT {

    @Autowired
    private TrendScoreService trendScoreService;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID categoria;
    private UUID usuario;
    private UUID direccion;

    @AfterEach
    void limpiar() {
        jdbc.update("DELETE FROM order_item WHERE order_id IN"
                + " (SELECT id FROM customer_order WHERE user_id = ?)", usuario);
        jdbc.update("DELETE FROM customer_order WHERE user_id = ?", usuario);
        jdbc.update("DELETE FROM product WHERE category_id = ?", categoria);
        jdbc.update("DELETE FROM address WHERE id = ?", direccion);
        jdbc.update("DELETE FROM users WHERE id = ?", usuario);
        jdbc.update("DELETE FROM category WHERE id = ?", categoria);
    }

    @BeforeEach
    void escenario() {
        // El recálculo va apagado en el perfil de test para que el cron no compita con la limpieza de
        // tablas entre casos; aquí se enciende porque es justo lo que se está probando.
        ReflectionTestUtils.setField(trendScoreService, "habilitado", true);
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
    @DisplayName("qué cuenta como venta: no lo cancelado, no lo reembolsado, no lo viejo")
    void queCuentaComoVenta() {
        UUID cancelado = producto("cancelado", BigDecimal.ZERO, "ACTIVE");
        UUID reembolsado = producto("reembolsado", BigDecimal.ZERO, "ACTIVE");
        UUID antiguo = producto("antiguo", BigDecimal.ZERO, "ACTIVE");
        UUID enElBorde = producto("borde", BigDecimal.ZERO, "ACTIVE");
        UUID vacio = producto("vacio", BigDecimal.ZERO, "ACTIVE");
        pedido(cancelado, 25, "CANCELLED", 2);
        pedido(reembolsado, 25, "REFUNDED", 2);
        pedido(antiguo, 25, "DELIVERED", 40);
        pedido(enElBorde, 10, "DELIVERED", 29);

        trendScoreService.recompute();

        // Un pedido cancelado o reembolsado no es una venta: contarlo pondría en «Tendencia ahora» algo
        // que nadie se quedó. Y una venta de hace 40 días tampoco, o la sección se congelaría igual que
        // estaba antes del arreglo.
        assertThat(scoreDe(cancelado)).isEqualByComparingTo("0");
        assertThat(scoreDe(reembolsado)).isEqualByComparingTo("0");
        assertThat(scoreDe(antiguo)).isEqualByComparingTo("0");
        assertThat(scoreDe(vacio)).isEqualByComparingTo("0");
        // Valor límite por el otro lado: 29 días SÍ entra.
        assertThat(scoreDe(enElBorde)).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("cómo se pondera: las ventas mandan, saturan y se suman entre pedidos")
    void comoSePondera() {
        UUID vende = producto("vende", new BigDecimal("3.0"), "ACTIVE");
        UUID soloEstrellas = producto("estrellas", new BigDecimal("5.0"), "ACTIVE");
        UUID mucho = producto("mucho", BigDecimal.ZERO, "ACTIVE");
        UUID muchisimo = producto("muchisimo", BigDecimal.ZERO, "ACTIVE");
        UUID uno = producto("uno", BigDecimal.ZERO, "ACTIVE");
        UUID troceado = producto("troceado", BigDecimal.ZERO, "ACTIVE");
        pedido(vende, 20, "DELIVERED", 3);
        pedido(mucho, 25, "DELIVERED", 1);
        pedido(muchisimo, 5000, "DELIVERED", 1);
        pedido(uno, 10, "DELIVERED", 1);
        pedido(troceado, 4, "DELIVERED", 1);
        pedido(troceado, 6, "DELIVERED", 2);

        trendScoreService.recompute();

        // Vender gana a estar bien valorado: es la razón de ser del cambio. Antes encabezaba la sección
        // quien tenía el dato de 1688, no quien vendía.
        assertThat(scoreDe(vende)).isGreaterThan(scoreDe(soloEstrellas));
        // Sin tope, un único pedido mayorista dejaría al resto del catálogo pegado al cero para siempre.
        assertThat(scoreDe(mucho)).isEqualByComparingTo(scoreDe(muchisimo));
        // Diez unidades en un pedido o repartidas en dos: la demanda es la misma.
        assertThat(scoreDe(troceado)).isEqualByComparingTo(scoreDe(uno));
    }

    @Test
    @DisplayName("no toca los inactivos y recalcular dos veces da lo mismo")
    void alcanceEIdempotencia() {
        UUID inactivo = producto("inactivo", new BigDecimal("5.0"), "DRAFT");
        UUID estable = producto("estable", new BigDecimal("4.0"), "ACTIVE");
        jdbc.update("UPDATE product SET trend_score = 0.9999 WHERE id = ?", inactivo);
        pedido(estable, 7, "DELIVERED", 5);

        trendScoreService.recompute();
        BigDecimal primera = scoreDe(estable);
        trendScoreService.recompute();

        // Un inactivo no compite en el escaparate: se le deja lo que tuviera.
        assertThat(scoreDe(inactivo)).isEqualByComparingTo("0.9999");
        // El barrido corre cada noche sobre el catálogo entero: si cada pasada moviera los valores, el
        // orden del escaparate cambiaría solo, sin que nadie hubiera comprado nada.
        assertThat(scoreDe(estable)).isEqualByComparingTo(primera);
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
