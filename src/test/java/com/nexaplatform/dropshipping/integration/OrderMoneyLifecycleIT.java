package com.nexaplatform.dropshipping.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El DINERO del ciclo de vida del pedido: cobro, reembolso, cancelación, comisión del afiliado y
 * comisión del operador, comprobados SIEMPRE con el importe exacto calculado a mano, al céntimo.
 *
 * <p>La regla de esta clase es que un 200 no prueba nada. Un reembolso que responde 200 y abona un
 * céntimo de menos —o el doble— sigue siendo un fallo, y es exactamente la clase de fallo que se cuela
 * cuando solo se comprueba el código de estado. Por eso cada caso contrasta el saldo del monedero, el
 * libro de movimientos y el importe registrado, no la respuesta HTTP.
 *
 * <p>Los importes de referencia salen de {@link OrderLifecycleSupport#totalEsperadoCents(int)}: producto
 * (25,00 $/ud) + porte plano (5,00 $) + IVA del 21% sobre la suma. Una unidad = 36,30 $; dos = 66,55 $.
 */
class OrderMoneyLifecycleIT extends OrderLifecycleSupport {

    private static final String ADMIN = "ADMIN";
    private static final String OPERATOR = "OPERATOR";

    /** Clave con la que el reembolso del admin sella su abono (idempotencia del movimiento). */
    private static final String CLAVE_REEMBOLSO = "refund-";
    /** Clave con la que la cancelación sella su abono. */
    private static final String CLAVE_CANCELACION = "cancel-";

    /* ==================== Cobro ==================== */

    @Test
    @DisplayName("pagar con saldo descuenta EXACTAMENTE el total del pedido (2 uds = 66,55 $) y cuadra el libro")
    void elCobroDescuentaElImporteExacto() {
        UUID productoId = sembrarEscenarioDeCompra();
        UUID compradorId = crearCompradorConSaldo();
        int esperado = totalEsperadoCents(2); // 5000 producto + 500 porte + 1155 IVA = 6655

        Map<String, Object> creado = checkout(compradorId, productoId, 2, "WALLET", null);
        UUID pedidoId = UUID.fromString(String.valueOf(creado.get("id")));

        assertThat(esperado).as("el importe de referencia se calcula a mano, no se lee del sistema").isEqualTo(6655);
        assertThat(totalDe(pedidoId)).as("total guardado en el pedido").isEqualTo(esperado);
        assertThat(new BigDecimal(String.valueOf(creado.get("total"))))
                .as("total devuelto por la API, en unidades de divisa").isEqualByComparingTo(new BigDecimal("66.55"));
        assertThat(saldoDe(compradorId)).as("saldo tras el cobro").isEqualTo(SALDO_INICIAL_CENTS - esperado);
        assertThat(sumaMovimientos(compradorId)).as("el libro tiene que cuadrar con el saldo movido")
                .isEqualTo(-(long) esperado);
    }

    /* ==================== Reembolso del admin ==================== */

    @Test
    @DisplayName("el reembolso del admin devuelve EXACTAMENTE lo pagado: el saldo vuelve al importe de partida")
    void elReembolsoDevuelveLoPagadoAlCentimo() {
        UUID productoId = sembrarEscenarioDeCompra();
        UUID compradorId = crearCompradorConSaldo();
        int esperado = totalEsperadoCents(2);
        UUID pedidoId = pedidoPagado(compradorId, productoId, 2);
        long saldoTrasCobro = saldoDe(compradorId);

        assertThat(transicionAdmin(pedidoId, "refund", jwt.userToken(ADMIN))).isEqualTo(200);

        assertThat(estadoDe(pedidoId)).isEqualTo("REFUNDED");
        assertThat(saldoDe(compradorId) - saldoTrasCobro).as("el abono tiene que ser el total, ni un céntimo más")
                .isEqualTo(esperado);
        assertThat(saldoDe(compradorId)).as("y por tanto el saldo vuelve exactamente al de partida")
                .isEqualTo(SALDO_INICIAL_CENTS);
        assertThat(sumaMovimientos(compradorId)).as("cobro y abono se anulan en el libro").isZero();
        assertThat(abonosCon(pedidoId, CLAVE_REEMBOLSO)).as("un único apunte de reembolso").isEqualTo(1);
    }

    @Test
    @DisplayName("el reembolso NO acepta importe: un importe inyectado en el cuerpo se ignora y se devuelve el pedido entero")
    void elImporteInyectadoEnElCuerpoSeIgnora() {
        UUID productoId = sembrarEscenarioDeCompra();
        UUID compradorId = crearCompradorConSaldo();
        int esperado = totalEsperadoCents(2);
        UUID pedidoId = pedidoPagado(compradorId, productoId, 2);
        long saldoTrasCobro = saldoDe(compradorId);

        // El endpoint no declara cuerpo: reembolsa el pedido COMPLETO. Se le cuela un importe distinto por
        // todos los nombres plausibles para asegurarse de que ninguno se abre paso hasta el abono — ni uno
        // ridículo (que dejaría al cliente sin su dinero) ni uno enorme (que vaciaría la caja).
        Map<String, Object> cuerpoMalicioso = new LinkedHashMap<>();
        cuerpoMalicioso.put("amountCents", 1);
        cuerpoMalicioso.put("amount", 999_999);
        cuerpoMalicioso.put("totalCents", 999_999);
        cuerpoMalicioso.put("refundAmountCents", 999_999);

        assertThat(transicionAdminConCuerpo(pedidoId, "refund", jwt.userToken(ADMIN), cuerpoMalicioso)).isEqualTo(200);

        assertThat(saldoDe(compradorId) - saldoTrasCobro)
                .as("el abono es el total real del pedido, no el importe inyectado").isEqualTo(esperado);
        assertThat(saldoDe(compradorId)).isEqualTo(SALDO_INICIAL_CENTS);
    }

    @Test
    @DisplayName("el reembolso es idempotente: repetirlo sobre un pedido ya REFUNDED no duplica el abono")
    void elReembolsoRepetidoNoDuplicaElAbono() {
        UUID productoId = sembrarEscenarioDeCompra();
        UUID compradorId = crearCompradorConSaldo();
        UUID pedidoId = pedidoPagado(compradorId, productoId, 2);
        String admin = jwt.userToken(ADMIN);

        assertThat(transicionAdmin(pedidoId, "refund", admin)).isEqualTo(200);
        long saldoTrasElPrimero = saldoDe(compradorId);

        // Tres reintentos más: un doble clic, un reintento de red y una repetición manual.
        assertThat(transicionAdmin(pedidoId, "refund", admin)).isEqualTo(200);
        assertThat(transicionAdmin(pedidoId, "refund", admin)).isEqualTo(200);
        assertThat(transicionAdmin(pedidoId, "refund", admin)).isEqualTo(200);

        assertThat(saldoDe(compradorId)).as("el saldo no se mueve en los reintentos").isEqualTo(saldoTrasElPrimero)
                .isEqualTo(SALDO_INICIAL_CENTS);
        assertThat(abonosCon(pedidoId, CLAVE_REEMBOLSO)).as("un solo apunte de reembolso en el libro").isEqualTo(1);
        assertThat(sumaMovimientos(compradorId)).as("el libro sigue cuadrado").isZero();
    }

    @Test
    @DisplayName("un pedido cancelado no se puede reembolsar: 422 y ni un céntimo se mueve")
    void noSeReembolsaUnCancelado() {
        UUID productoId = sembrarEscenarioDeCompra();
        UUID compradorId = crearCompradorConSaldo();
        UUID pedidoId = pedidoPagado(compradorId, productoId, 2);
        String admin = jwt.userToken(ADMIN);

        assertThat(transicionAdmin(pedidoId, "cancel", admin)).isEqualTo(200);
        long saldoTrasCancelar = saldoDe(compradorId);

        assertThat(transicionAdmin(pedidoId, "refund", admin)).as("reembolsar lo ya cancelado sería pagar dos veces")
                .isEqualTo(422);

        assertThat(saldoDe(compradorId)).isEqualTo(saldoTrasCancelar).isEqualTo(SALDO_INICIAL_CENTS);
        assertThat(abonosCon(pedidoId, CLAVE_REEMBOLSO)).as("no hay apunte de reembolso").isZero();
        assertThat(abonosCon(pedidoId, CLAVE_CANCELACION)).as("solo el de la cancelación").isEqualTo(1);
    }

    @Test
    @DisplayName("cancelar un pedido YA reembolsado no vuelve a abonar nada")
    void cancelarUnReembolsadoNoAbonaOtraVez() {
        UUID productoId = sembrarEscenarioDeCompra();
        UUID compradorId = crearCompradorConSaldo();
        UUID pedidoId = pedidoPagado(compradorId, productoId, 2);
        String admin = jwt.userToken(ADMIN);

        assertThat(transicionAdmin(pedidoId, "refund", admin)).isEqualTo(200);
        assertThat(transicionAdmin(pedidoId, "cancel", admin)).isEqualTo(200);

        assertThat(estadoDe(pedidoId)).isEqualTo("CANCELLED");
        assertThat(saldoDe(compradorId)).as("el cliente ya cobró su reembolso: no cobra dos veces")
                .isEqualTo(SALDO_INICIAL_CENTS);
        assertThat(abonosCon(pedidoId, CLAVE_CANCELACION)).as("la cancelación no genera abono propio").isZero();
    }

    /* ==================== Cancelaciones ==================== */

    @Test
    @DisplayName("la cancelación del admin sobre un pedido PAGADO devuelve el total exacto")
    void laCancelacionDelAdminDevuelveElTotal() {
        UUID productoId = sembrarEscenarioDeCompra();
        UUID compradorId = crearCompradorConSaldo();
        int esperado = totalEsperadoCents(1); // 2500 + 500 + 630 = 3630
        UUID pedidoId = pedidoPagado(compradorId, productoId, 1);
        long saldoTrasCobro = saldoDe(compradorId);

        assertThat(esperado).isEqualTo(3630);
        assertThat(transicionAdmin(pedidoId, "cancel", jwt.userToken(ADMIN))).isEqualTo(200);

        assertThat(saldoDe(compradorId) - saldoTrasCobro).isEqualTo(esperado);
        assertThat(saldoDe(compradorId)).isEqualTo(SALDO_INICIAL_CENTS);
        assertThat(abonosCon(pedidoId, CLAVE_CANCELACION)).isEqualTo(1);
    }

    @Test
    @DisplayName("la cancelación del cliente al monedero devuelve el total exacto y solo una vez")
    void laCancelacionDelClienteDevuelveElTotal() {
        UUID productoId = sembrarEscenarioDeCompra();
        UUID compradorId = crearCompradorConSaldo();
        int esperado = totalEsperadoCents(2);
        UUID pedidoId = pedidoPagado(compradorId, productoId, 2);

        assertThat(cancelarComoCliente(compradorId, pedidoId, true)).isEqualTo(200);

        assertThat(saldoDe(compradorId)).isEqualTo(SALDO_INICIAL_CENTS);
        assertThat(abonosCon(pedidoId, CLAVE_CANCELACION)).isEqualTo(1);
        assertThat(sumaMovimientos(compradorId)).isZero();

        // Reintentar la cancelación ya no procede (el pedido está CANCELLED) y no puede abonar de nuevo.
        assertThat(cancelarComoCliente(compradorId, pedidoId, true)).isEqualTo(422);
        assertThat(saldoDe(compradorId)).isEqualTo(SALDO_INICIAL_CENTS);
        assertThat(abonosCon(pedidoId, CLAVE_CANCELACION)).isEqualTo(1);
        assertThat(totalDe(pedidoId)).as("el total del pedido no se toca al cancelar").isEqualTo(esperado);
    }

    @Test
    @DisplayName("cancelar un pedido NO pagado no abona nada al monedero")
    void cancelarUnPedidoSinPagarNoAbonaNada() {
        UUID productoId = sembrarEscenarioDeCompra();
        UUID compradorId = crearCompradorConSaldo();
        UUID pedidoId = pedidoSinPagar(compradorId, productoId, 2);

        assertThat(transicionAdmin(pedidoId, "cancel", jwt.userToken(ADMIN))).isEqualTo(200);

        assertThat(estadoDe(pedidoId)).isEqualTo("CANCELLED");
        assertThat(saldoDe(compradorId)).as("nunca se cobró, así que no hay nada que devolver")
                .isEqualTo(SALDO_INICIAL_CENTS);
        assertThat(sumaMovimientos(compradorId)).as("ni un solo movimiento en el libro").isZero();
    }

    @Test
    @DisplayName("SOSPECHOSO: reembolsar un pedido NO pagado abona igualmente el total al monedero (dinero de la nada)")
    void reembolsarUnPedidoSinPagarAbonaDinero() {
        UUID productoId = sembrarEscenarioDeCompra();
        UUID compradorId = crearCompradorConSaldo();
        int total = totalEsperadoCents(2);
        UUID pedidoId = pedidoSinPagar(compradorId, productoId, 2);
        assertThat(saldoDe(compradorId)).as("el pedido con tarjeta no cobró nada").isEqualTo(SALDO_INICIAL_CENTS);

        assertThat(transicionAdmin(pedidoId, "refund", jwt.userToken(ADMIN))).isEqualTo(200);

        // refundOrder no comprueba que el pedido estuviera PAGADO: solo descarta REFUNDED y CANCELLED.
        // Con un pedido PENDING (pago externo que nunca se completó) no hay pago que devolver, y aun así
        // acredita el total al monedero del cliente. La cancelación, en cambio, sí distingue (ver el test
        // de arriba). Esto se deja EN VERDE porque documenta lo que hace hoy el sistema, pero es dinero
        // creado de la nada y debería exigir que el pedido estuviera pagado, igual que hace cancelOrder.
        assertThat(saldoDe(compradorId)).as("comportamiento ACTUAL: el monedero crece sin que hubiera habido cobro")
                .isEqualTo(SALDO_INICIAL_CENTS + total);
        assertThat(sumaMovimientos(compradorId)).isEqualTo(total);
    }

    @Test
    @DisplayName("dos cancelaciones simultáneas del mismo pedido abonan el dinero UNA sola vez")
    void dosCancelacionesSimultaneasAbonanUnaVez() throws Exception {
        UUID productoId = sembrarEscenarioDeCompra();
        UUID compradorId = crearCompradorConSaldo();
        UUID pedidoId = pedidoPagado(compradorId, productoId, 2);
        String admin = jwt.userToken(ADMIN);

        // Dos peticiones a la vez es el escenario real del doble clic y del reintento del navegador. El
        // abono se sella con la misma clave de idempotencia, así que solo puede prosperar uno.
        List<Callable<Integer>> intentos = new ArrayList<>();
        intentos.add(() -> transicionAdmin(pedidoId, "cancel", admin));
        intentos.add(() -> transicionAdmin(pedidoId, "cancel", admin));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> resultados = pool.invokeAll(intentos);
        for (Future<Integer> r : resultados) {
            assertThat(r.get()).as("ninguna de las dos puede reventar con un 500").isLessThan(500);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(estadoDe(pedidoId)).isEqualTo("CANCELLED");
        assertThat(abonosCon(pedidoId, CLAVE_CANCELACION)).as("un único abono pase lo que pase").isEqualTo(1);
        assertThat(saldoDe(compradorId)).as("el saldo vuelve al de partida, no al doble")
                .isEqualTo(SALDO_INICIAL_CENTS);
    }

    /* ==================== Comisión del afiliado ==================== */

    @Test
    @DisplayName("con referido: el comprador paga 60,50 $ (10% menos), el afiliado gana 4,50 $ y el reembolso anula la comisión")
    void elReembolsoAnulaLaComisionDelAfiliado() {
        UUID productoId = sembrarEscenarioDeCompra();
        UUID compradorId = crearCompradorConSaldo();
        Map<String, UUID> afiliado = sembrarAfiliadoQueRefiere(compradorId);
        UUID afiliadoId = afiliado.get("affiliateId");

        // Cálculo a mano con descuento de referido:
        //   producto 5000 − 10% (500) = 4500 · porte 500 · IVA 21% de (4500+500) = 1050 → total 6050
        //   comisión del afiliado = 10% de la base cobrada por producto (4500) = 450
        int totalConDescuento = 6050;
        long comisionEsperada = 450L;

        UUID pedidoId = pedidoPagado(compradorId, productoId, 2);

        assertThat(totalDe(pedidoId)).as("el descuento de referido tiene que estar dentro del total cobrado")
                .isEqualTo(totalConDescuento);
        assertThat(saldoDe(compradorId)).isEqualTo(SALDO_INICIAL_CENTS - totalConDescuento);
        assertThat(importeComisionAfiliado(pedidoId)).as("comisión generada").isEqualTo(comisionEsperada);
        assertThat(estadoComisionAfiliado(pedidoId)).isEqualTo("PENDING");
        assertThat(gananciasAfiliado(afiliadoId)).isEqualTo(comisionEsperada);

        assertThat(transicionAdmin(pedidoId, "refund", jwt.userToken(ADMIN))).isEqualTo(200);

        assertThat(estadoComisionAfiliado(pedidoId)).as("la venta se deshizo: la comisión se anula")
                .isEqualTo("REJECTED");
        assertThat(estadoConversionAfiliado(pedidoId)).isEqualTo("CANCELLED");
        assertThat(gananciasAfiliado(afiliadoId)).as("y se le descuenta al afiliado, al céntimo").isZero();
        assertThat(saldoDe(compradorId)).as("al cliente se le devuelve lo que pagó de verdad")
                .isEqualTo(SALDO_INICIAL_CENTS);
    }

    @Test
    @DisplayName("cancelar el pedido también anula la comisión del afiliado y le descuenta las ganancias")
    void laCancelacionAnulaLaComisionDelAfiliado() {
        UUID productoId = sembrarEscenarioDeCompra();
        UUID compradorId = crearCompradorConSaldo();
        Map<String, UUID> afiliado = sembrarAfiliadoQueRefiere(compradorId);
        UUID afiliadoId = afiliado.get("affiliateId");
        UUID pedidoId = pedidoPagado(compradorId, productoId, 2);
        assertThat(gananciasAfiliado(afiliadoId)).isEqualTo(450L);

        assertThat(cancelarComoCliente(compradorId, pedidoId, true)).isEqualTo(200);

        assertThat(estadoComisionAfiliado(pedidoId)).isEqualTo("REJECTED");
        assertThat(estadoConversionAfiliado(pedidoId)).isEqualTo("CANCELLED");
        assertThat(gananciasAfiliado(afiliadoId)).isZero();
        assertThat(saldoDe(compradorId)).isEqualTo(SALDO_INICIAL_CENTS);
    }

    /* ==================== Comisión del operador ==================== */

    @Test
    @DisplayName("la comisión del operador NO existe antes de entregar y aparece justo al entregar, con el importe exacto")
    void laComisionDelOperadorSeGanaAlEntregar() {
        UUID productoId = sembrarEscenarioDeCompra();
        UUID compradorId = crearCompradorConSaldo();
        UUID operadorId = crearUsuario(OPERATOR);
        String operador = tokenDe(operadorId, OPERATOR);
        UUID pedidoId = pedidoPagado(compradorId, productoId, 2);

        // Cálculo a mano (ver OperatorCommissionService):
        //   coste congelado por unidad 2500 · 2 uds = 5000
        //   base sin IVA chino (13%): 5000 / 1,13 = 4424,7788
        //   comisión de una orden PROPIA (PLATFORM) = 10% → 442,4779 → 442 céntimos
        long comisionEsperada = 442L;

        assertThat(comisionesDelOperador(operadorId)).as("recién pagado: nada ganado").isZero();
        assertThat(transicionAdmin(pedidoId, "forward", operador)).isEqualTo(200);
        assertThat(comisionesDelOperador(operadorId)).as("reenviar al proveedor no paga comisión").isZero();
        assertThat(transicionAdmin(pedidoId, "ship", operador)).isEqualTo(200);
        assertThat(comisionesDelOperador(operadorId)).as("marcar en camino tampoco").isZero();

        assertThat(transicionAdmin(pedidoId, "deliver", operador)).isEqualTo(200);

        assertThat(comisionesDelOperador(operadorId)).as("solo al ENTREGAR se registra la operación").isEqualTo(1);
        Map<String, Object> accion = jdbcTemplate.queryForMap("SELECT commission_cny_cents, item_count,"
                + " action, order_source, commission_pct, operator_email FROM operator_order_action"
                + " WHERE order_id = ?", pedidoId);
        assertThat(((Number) accion.get("commission_cny_cents")).longValue())
                .as("el código aplica el 10 por ciento de la base SIN IVA a las órdenes propias")
                .isEqualTo(comisionEsperada);
        assertThat(((Number) accion.get("item_count")).intValue()).as("unidades procesadas").isEqualTo(2);
        assertThat(accion.get("action")).isEqualTo("DELIVERED");
        assertThat(accion.get("order_source")).as("pedido del escaparate propio").isEqualTo("PLATFORM");
        assertThat(new BigDecimal(String.valueOf(accion.get("commission_pct"))))
                .isEqualByComparingTo(new BigDecimal("10"));
        assertThat(accion.get("operator_email")).as("la comisión queda a nombre de QUIEN entregó")
                .isEqualTo(correoDe(operadorId));

        Map<String, Object> ganancias = gananciasDelOperador(operador);
        assertThat(((Number) ganancias.get("totalCommissionCnyCents")).longValue())
                .as("lo que el operador ve en su panel, al céntimo").isEqualTo(comisionEsperada);
        assertThat(((Number) ganancias.get("operations")).longValue()).isEqualTo(1L);
        assertThat(ganancias.get("currency")).isEqualTo("CNY");
    }

    @Test
    @DisplayName("entregar dos veces no duplica la comisión del operador")
    void entregarDosVecesNoDuplicaLaComision() {
        UUID productoId = sembrarEscenarioDeCompra();
        UUID compradorId = crearCompradorConSaldo();
        UUID operadorId = crearUsuario(OPERATOR);
        String operador = tokenDe(operadorId, OPERATOR);
        UUID pedidoId = pedidoPagado(compradorId, productoId, 2);
        avanzarHasta(pedidoId, "DELIVERED", operador);

        assertThat(transicionAdmin(pedidoId, "deliver", operador))
                .as("el segundo intento se rechaza por la máquina de estados").isEqualTo(422);

        assertThat(comisionesDelOperador(operadorId)).isEqualTo(1);
        assertThat(((Number) gananciasDelOperador(operador).get("totalCommissionCnyCents")).longValue())
                .isEqualTo(442L);
    }

    @Test
    @DisplayName("la comisión es de quien entrega: otro operador no se lleva nada del pedido ajeno")
    void laComisionEsDeQuienEntrega() {
        UUID productoId = sembrarEscenarioDeCompra();
        UUID compradorId = crearCompradorConSaldo();
        UUID entregaId = crearUsuario(OPERATOR);
        UUID mironId = crearUsuario(OPERATOR);
        String entrega = tokenDe(entregaId, OPERATOR);
        String miron = tokenDe(mironId, OPERATOR);
        UUID pedidoId = pedidoPagado(compradorId, productoId, 2);

        avanzarHasta(pedidoId, "DELIVERED", entrega);

        assertThat(((Number) gananciasDelOperador(entrega).get("totalCommissionCnyCents")).longValue()).isEqualTo(442L);
        assertThat(((Number) gananciasDelOperador(miron).get("totalCommissionCnyCents")).longValue())
                .as("el operador que no entregó no gana nada").isZero();
        assertThat(comisionesDelOperador(mironId)).isZero();
    }

    @Test
    @DisplayName("un pedido cancelado o reembolsado nunca llega a generar comisión de operador")
    void sinEntregaNoHayComisionDeOperador() {
        UUID productoId = sembrarEscenarioDeCompra();
        UUID compradorId = crearCompradorConSaldo();
        UUID operadorId = crearUsuario(OPERATOR);
        String operador = tokenDe(operadorId, OPERATOR);
        UUID pedidoId = pedidoPagado(compradorId, productoId, 2);

        avanzarHasta(pedidoId, "SHIPPED", operador);
        assertThat(transicionAdmin(pedidoId, "refund", jwt.userToken(ADMIN))).isEqualTo(200);

        assertThat(comisionesDelOperador(operadorId)).as("sin entrega no hay comisión").isZero();
        assertThat(transicionAdmin(pedidoId, "deliver", operador)).as("y ya no se puede entregar un pedido reembolsado")
                .isEqualTo(422);
        assertThat(comisionesDelOperador(operadorId)).isZero();
    }

    /* ==================== Inventario ==================== */

    @Test
    @DisplayName("el stock NO se mueve al comprar ni al cancelar/reembolsar: en dropshipping el inventario es del proveedor")
    void elStockNoSeMueveEnNingunMomento() {
        UUID productoId = sembrarEscenarioDeCompra();
        UUID varianteId = variantePrincipal(productoId);
        UUID compradorId = crearCompradorConSaldo();
        int stockInicial = stockDe(varianteId);
        assertThat(stockInicial).isEqualTo(40);

        UUID pedidoId = pedidoPagadoConVariante(compradorId, productoId, varianteId, 2);
        assertThat(stockDe(varianteId)).as("la venta no descuenta unidades").isEqualTo(stockInicial);

        assertThat(transicionAdmin(pedidoId, "refund", jwt.userToken(ADMIN))).isEqualTo(200);

        // El "devolver el stock" del reembolso pasa por StockService.restoreForOrder, que es un no-op
        // DELIBERADO: la plataforma no mantiene inventario propio, el proveedor abastece bajo demanda y el
        // número de stock es informativo. La invariante correcta, por tanto, es que NO cambie nunca; si
        // algún día vuelve a descontarse, esta prueba lo caza tanto en la venta como en la devolución.
        assertThat(stockDe(varianteId)).as("y la devolución tampoco lo repone, porque nunca se descontó")
                .isEqualTo(stockInicial);

        UUID segundoPedido = pedidoPagadoConVariante(compradorId, productoId, varianteId, 3);
        assertThat(cancelarComoCliente(compradorId, segundoPedido, true)).isEqualTo(200);
        assertThat(stockDe(varianteId)).as("mismo resultado por la vía de la cancelación del cliente")
                .isEqualTo(stockInicial);
    }

    /* ==================== Utilidades del bloque ==================== */

    /** Nº de operaciones registradas (y por tanto comisiones) del operador dado. */
    private int comisionesDelOperador(UUID operadorId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM operator_order_action" + " WHERE operator_subject = ?", Integer.class,
                operadorId.toString());
        return n == null ? 0 : n;
    }
}
