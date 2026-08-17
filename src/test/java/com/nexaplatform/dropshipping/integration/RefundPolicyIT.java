package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.application.service.RefundPolicy;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cuánto vuelve al monedero cuando un pedido CON ARANCEL PAGADO se cancela o se reembolsa, recorrido por
 * HTTP y contrastado contra el saldo real de la base de datos.
 *
 * <p><b>Por qué hace falta y no basta {@code RefundableAmountTest}.</b> El unitario construye un
 * {@code Order} a mano y le pone el arancel con un {@code setter}: comprueba la aritmética de
 * {@link RefundPolicy}, no que el arancel llegue a cobrarse, ni que se guarde en la columna que la
 * política lee, ni que el importe que sale de ahí sea el que de verdad se abona. Y
 * {@code OrderMoneyLifecycleIT}, que sí recorre cobros y reembolsos por HTTP, lo hace SIN regla aduanera
 * sembrada: todos sus pedidos llevan {@code customs_duty_cents = 0}, así que la resta del arancel nunca
 * se ejercita y podría estar rota sin que nadie se enterase.
 *
 * <p><b>La regla que se certifica aquí.</b> El derecho fijo de la Unión lo cobra el transportista al dar
 * entrada al paquete en su almacén y no lo reintegra por ningún motivo. Quién lo asume depende de por
 * qué se devuelve el pedido:
 * <ul>
 *   <li><b>Desistimiento</b>: el artículo 13 de la Directiva 2011/83/UE obliga a reembolsar todos los
 *       pagos recibidos, así que el arancel lo pierde el comercio y el cliente cobra el total.</li>
 *   <li><b>Causa imputable al cliente</b>: se descuenta, porque ya está pagado y no se recupera.</li>
 * </ul>
 * Y el momento importa: hasta {@code FORWARDED} no hay nada pagado al transportista, así que antes de
 * despachar se devuelve íntegro en los dos casos.
 *
 * <p><b>Los importes de referencia</b>, todos calculados a mano (2 unidades):
 * producto 2 × 25,00 $ = 50,00 $; porte plano 5,00 $; IVA del 21% sobre 55,00 $ = 11,55 $; arancel
 * 3,00 $ (una sola partida arancelaria). Total <b>69,55 $</b>, de los cuales <b>3,00 $</b> son el arancel
 * que la política puede llegar a retener. Si el abono difiere en un céntimo, es un fallo de dinero.
 */
class RefundPolicyIT extends OrderLifecycleSupport {

    private static final String ADMIN = "ADMIN";

    /** Clave con la que la cancelación sella su abono al monedero (idempotencia del movimiento). */
    private static final String CLAVE_CANCELACION = "cancel-";
    /** Clave con la que el reembolso del admin sella el suyo. */
    private static final String CLAVE_REEMBOLSO = "refund-";

    /**
     * El derecho fijo de la Unión, en céntimos USD.
     *
     * <p>Se siembra en DÓLARES y no en euros a propósito. El importe legal son 3 EUR y su conversión
     * —tasa del día, caché de divisas de cinco minutos que sobrevive al TRUNCATE— ya está certificada al
     * céntimo en {@code CustomsDutyIT}. Repetirla aquí solo añadiría una fuente de números inestables a
     * una prueba que mide otra cosa: cuánto se devuelve del arancel, no cuánto vale.
     */
    private static final int ARANCEL_CENTS = 300;

    /** Unidades de todos los pedidos de la clase; los importes de referencia se calculan sobre ellas. */
    private static final int UNIDADES = 2;

    @Autowired
    private OrderRepository pedidos;

    @Autowired
    private PlatformTransactionManager transacciones;

    /** Total del pedido con arancel: el de siempre más el derecho, que viaja dentro del envío. */
    private static int totalConArancelCents() {
        return totalEsperadoCents(UNIDADES) + ARANCEL_CENTS;
    }

    /* ==================================================================================
     *  A · Antes de despachar: no hay arancel pagado, se devuelve íntegro
     * ================================================================================== */

    @Test
    @DisplayName("cancelando antes de despachar, el cliente recupera hasta el último céntimo: nada se ha pagado aún")
    void antesDeDespacharElClienteRecuperaTodo() {
        UUID productoId = escenarioConArancel();
        UUID compradorId = crearCompradorConSaldo();
        UUID pedidoId = pedidoPagado(compradorId, productoId, UNIDADES);
        long saldoTrasCobro = saldoDe(compradorId);

        // El pedido tiene que llevar arancel de verdad: sin él esta prueba no probaría nada.
        assertThat(arancelDe(pedidoId)).as("el pedido lleva cobrado el derecho de la Unión")
                .isEqualTo(ARANCEL_CENTS);
        assertThat(totalDe(pedidoId)).isEqualTo(totalConArancelCents());

        assertThat(cancelarComoCliente(compradorId, pedidoId, true)).isEqualTo(200);

        assertThat(estadoDe(pedidoId)).isEqualTo("CANCELLED");
        assertThat(saldoDe(compradorId) - saldoTrasCobro)
                .as("el paquete no salió: se devuelve el total, arancel incluido")
                .isEqualTo(totalConArancelCents());
        assertThat(saldoDe(compradorId)).as("el monedero vuelve exactamente al saldo de partida")
                .isEqualTo(SALDO_INICIAL_CENTS);
        assertThat(sumaMovimientos(compradorId)).as("cobro y abono se anulan en el libro").isZero();
        assertThat(abonosCon(pedidoId, CLAVE_CANCELACION)).as("un único apunte de cancelación").isEqualTo(1);
    }

    /* ==================================================================================
     *  B · Ya despachado: el desistimiento lo devuelve TODO igualmente
     * ================================================================================== */

    @Test
    @DisplayName("una vez despachado (FORWARDED), el desistimiento devuelve también el arancel: lo asume el comercio")
    void despachadoElDesistimientoDevuelveTambienElArancel() {
        UUID productoId = escenarioConArancel();
        UUID compradorId = crearCompradorConSaldo();
        UUID pedidoId = pedidoPagado(compradorId, productoId, UNIDADES);
        long saldoTrasCobro = saldoDe(compradorId);
        avanzarHasta(pedidoId, "FORWARDED", jwt.userToken(ADMIN));

        assertThat(transicionAdmin(pedidoId, "cancel", jwt.userToken(ADMIN))).isEqualTo(200);

        assertThat(saldoDe(compradorId) - saldoTrasCobro)
                .as("la Directiva 2011/83/UE obliga a devolver todos los pagos recibidos")
                .isEqualTo(totalConArancelCents());
        assertThat(saldoDe(compradorId) - saldoTrasCobro)
                .as("retener aquí los 3,00 $ del arancel sería probablemente una cláusula abusiva")
                .isNotEqualTo(totalConArancelCents() - ARANCEL_CENTS);
        assertThat(saldoDe(compradorId)).isEqualTo(SALDO_INICIAL_CENTS);
    }

    @Test
    @DisplayName("con el pedido ya entregado la cancelación sigue devolviendo el total, arancel incluido")
    void entregadoTambienSeDevuelveElTotal() {
        UUID productoId = escenarioConArancel();
        UUID compradorId = crearCompradorConSaldo();
        UUID pedidoId = pedidoPagado(compradorId, productoId, UNIDADES);
        long saldoTrasCobro = saldoDe(compradorId);
        avanzarHasta(pedidoId, "DELIVERED", jwt.userToken(ADMIN));

        assertThat(transicionAdmin(pedidoId, "cancel", jwt.userToken(ADMIN))).isEqualTo(200);

        assertThat(saldoDe(compradorId) - saldoTrasCobro).isEqualTo(totalConArancelCents());
        assertThat(abonosCon(pedidoId, CLAVE_CANCELACION)).isEqualTo(1);
    }

    /* ==================================================================================
     *  C · El momento en que el arancel empieza a contar
     * ================================================================================== */

    @Test
    @DisplayName("el arancel solo se puede retener a partir de FORWARDED, que es cuando el transportista lo cobra")
    void elArancelSoloCuentaDesdeQueElTransportistaLoCobra() {
        UUID productoId = escenarioConArancel();
        UUID compradorId = crearCompradorConSaldo();
        UUID pedidoId = pedidoPagado(compradorId, productoId, UNIDADES);
        int total = totalConArancelCents();

        // El pedido REAL, tal y como quedó persistido tras el cobro: es el que la política lee en
        // producción, con su total y su arancel escritos por el checkout y no por el test.
        Order pagado = pedidoPersistido(pedidoId);
        assertThat(RefundPolicy.refundableCents(pagado, RefundPolicy.Reason.WITHDRAWAL)).isEqualTo(total);
        assertThat(RefundPolicy.refundableCents(pagado, RefundPolicy.Reason.CUSTOMER_FAULT))
                .as("aún no ha entrado en el almacén del transportista: no hay arancel que retener")
                .isEqualTo(total);

        avanzarHasta(pedidoId, "FORWARDED", jwt.userToken(ADMIN));

        Order despachado = pedidoPersistido(pedidoId);
        assertThat(RefundPolicy.refundableCents(despachado, RefundPolicy.Reason.WITHDRAWAL))
                .as("el desistimiento sigue devolviéndolo todo").isEqualTo(total);
        assertThat(RefundPolicy.refundableCents(despachado, RefundPolicy.Reason.CUSTOMER_FAULT))
                .as("por causa del cliente se retienen los 3,00 $ que el transportista no reintegra")
                .isEqualTo(total - ARANCEL_CENTS);
    }

    /* ==================================================================================
     *  D · Lo que sostiene la regla: el arancel guardado aparte
     * ================================================================================== */

    @Test
    @DisplayName("el arancel se cobra dentro del envío pero se guarda aparte, que es lo único que el reembolso puede restar")
    void elArancelSeCobraEnElEnvioPeroSeGuardaAparte() {
        UUID productoId = escenarioConArancel();
        UUID compradorId = crearCompradorConSaldo();
        UUID pedidoId = pedidoPagado(compradorId, productoId, UNIDADES);

        // Sin esta columna la política no tendría de dónde restar y devolvería siempre el 100%: el fallo
        // sería invisible porque el total seguiría cuadrando con lo cobrado.
        assertThat(arancelDe(pedidoId)).isEqualTo(ARANCEL_CENTS);
        assertThat(envioDe(pedidoId)).as("el derecho viaja DENTRO del porte a efectos de cobro")
                .isEqualTo(ENVIO_CENTS + ARANCEL_CENTS);
        assertThat(totalDe(pedidoId)).isEqualTo(totalConArancelCents());
        assertThat(saldoDe(compradorId))
                .as("y el cliente ha pagado el arancel: sale de su monedero como parte del total")
                .isEqualTo(SALDO_INICIAL_CENTS - totalConArancelCents());
    }

    /* ==================================================================================
     *  E · Lo que hoy NO ocurre
     * ================================================================================== */

    @Test
    @DisplayName("SOSPECHOSO: ningún reembolso por HTTP llega a descontar el arancel, ni con el pedido entregado")
    void ningunReembolsoPorHttpDescuentaElArancel() {
        // `OrderUseCaseImpl.issueRefund` llama siempre con `Reason.WITHDRAWAL`, así que la rama
        // CUSTOMER_FAULT de la política —la que retiene el arancel— no la alcanza ningún endpoint: ni la
        // cancelación del cliente, ni la del admin, ni el reembolso. Para el desistimiento es lo correcto
        // y es lo que manda la Directiva; el problema es que el rechazo del paquete o la dirección
        // incorrecta se reembolsan igual, y esos 3,00 $ por pedido salen del margen sin que aparezcan en
        // ninguna cuenta. Queda escrito aquí para que el día que se abra esa vía este caso falle y haya
        // que decidirlo a conciencia, en vez de descubrirlo conciliando la factura del transportista.
        UUID productoId = escenarioConArancel();
        UUID compradorId = crearCompradorConSaldo();
        UUID pedidoId = pedidoPagado(compradorId, productoId, UNIDADES);
        long saldoTrasCobro = saldoDe(compradorId);
        avanzarHasta(pedidoId, "DELIVERED", jwt.userToken(ADMIN));

        // Las dos cifras se toman ANTES de reembolsar, con el pedido todavía en DELIVERED: es el estado
        // sobre el que la política decide. Después el pedido pasa a REFUNDED, que ya no es un estado
        // despachado, y las dos causas volverían a dar lo mismo.
        Order entregado = pedidoPersistido(pedidoId);
        int porDesistimiento = RefundPolicy.refundableCents(entregado, RefundPolicy.Reason.WITHDRAWAL);
        int porCausaDelCliente = RefundPolicy.refundableCents(entregado, RefundPolicy.Reason.CUSTOMER_FAULT);
        assertThat(porDesistimiento - porCausaDelCliente)
                .as("con el paquete entregado, las dos causas difieren exactamente en el arancel")
                .isEqualTo(ARANCEL_CENTS);

        assertThat(transicionAdmin(pedidoId, "refund", jwt.userToken(ADMIN))).isEqualTo(200);

        assertThat(saldoDe(compradorId) - saldoTrasCobro)
                .as("lo abonado coincide con el desistimiento, la única causa que el sistema sabe invocar")
                .isEqualTo(porDesistimiento);
        assertThat(saldoDe(compradorId) - saldoTrasCobro)
                .as("y por tanto NO coincide con lo que tocaría si la causa fuese del cliente")
                .isNotEqualTo(porCausaDelCliente);
        assertThat(abonosCon(pedidoId, CLAVE_REEMBOLSO)).isEqualTo(1);
    }

    /* ==================================================================================
     *  Siembra y lecturas
     * ================================================================================== */

    /**
     * Escenario de compra con arancel aduanero real en el destino. Devuelve el id del producto.
     *
     * <p>{@code BaseIntegration} vacía {@code country_customs_rule} antes de cada prueba, así que la regla
     * se siembra aquí: el derecho por PARTIDA arancelaria (3,00 $), que es como lo cobra la Unión, y una
     * franquicia de 150 $ muy por encima del pedido para que el régimen simplificado siga aplicando y el
     * recargo de despacho formal no se cuele en el total.
     */
    private UUID escenarioConArancel() {
        UUID productoId = sembrarEscenarioDeCompra();
        jdbcTemplate.update("INSERT INTO country_customs_rule (id, country_code, tax_mode,"
                + " de_minimis_amount, de_minimis_currency, over_threshold_policy, handling_fee_cents,"
                + " handling_percent_bps, per_article_fee_amount, per_article_fee_currency, active,"
                + " created_at, updated_at)"
                + " VALUES (?, ?, 'DDP', 150, 'USD', 'SURCHARGE', 0, 0, ?, 'USD', true, now(), now())",
                UUID.randomUUID(), PAIS, BigDecimal.valueOf(ARANCEL_CENTS, 2));
        return productoId;
    }

    /**
     * El pedido tal y como lo lee la aplicación: por el MISMO puerto de dominio que usa el caso de uso al
     * reembolsar, no reconstruido a mano en la prueba. Así, si el mapeo dejara de trasladar el arancel del
     * registro al modelo, la política vería un cero y estos casos lo cantarían.
     *
     * <p>Va dentro de una transacción porque el mapeo recorre las líneas del pedido, que son perezosas:
     * en producción siempre se lee desde un caso de uso transaccional y aquí no hay ninguno alrededor.
     */
    private Order pedidoPersistido(UUID pedidoId) {
        return new TransactionTemplate(transacciones).execute(estado -> pedidos.findById(pedidoId)
                .orElseThrow(() -> new IllegalStateException("No existe el pedido " + pedidoId)));
    }

    /** Derecho de aduana cobrado en el pedido (céntimos USD). */
    private int arancelDe(UUID pedidoId) {
        Integer valor = jdbcTemplate.queryForObject(
                "SELECT customs_duty_cents FROM customer_order WHERE id = ?", Integer.class, pedidoId);
        return valor == null ? 0 : valor;
    }

    /** Envío cobrado en el pedido, que ya incluye el arancel (céntimos USD). */
    private int envioDe(UUID pedidoId) {
        Integer valor = jdbcTemplate.queryForObject(
                "SELECT shipping_cents FROM customer_order WHERE id = ?", Integer.class, pedidoId);
        return valor == null ? 0 : valor;
    }
}
