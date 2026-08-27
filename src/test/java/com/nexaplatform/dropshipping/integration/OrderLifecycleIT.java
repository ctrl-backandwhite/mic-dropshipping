package com.nexaplatform.dropshipping.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MÁQUINA DE ESTADOS del pedido, recorrida por HTTP de punta a punta contra el contexto completo y un
 * Postgres real. Cubre la matriz entera: para cada estado, la transición que debe prosperar y TODAS las
 * que deben rechazarse, más los estados terminales, la cancelación del cliente, la separación de poderes
 * entre ADMIN y OPERATOR y la robustez ante entradas basura.
 *
 * <p>Existe porque el ciclo del pedido no tenía ninguna prueba de integración: se comprobaba a trozos con
 * dobles, y ahí no se ve ni el orden de los estados, ni quién puede ejecutar cada paso, ni qué pasa al
 * repetir una operación. Lo que toca dinero vive en {@code OrderMoneyLifecycleIT}; aquí solo el estado.
 *
 * <p><b>Nota sobre estados forzados.</b> Algunas casillas de la matriz no se alcanzan por endpoint
 * ({@code AWAITING_PAYMENT} no lo produce ningún flujo actual, y llegar a REFUNDED antes de probar
 * "reembolsar lo reembolsado" cuesta un pedido entero). En esos casos el estado se escribe directamente
 * en la base y se documenta: es preferible a dejar la casilla sin probar.
 */
class OrderLifecycleIT extends OrderLifecycleSupport {

    private static final String ADMIN = "ADMIN";
    private static final String OPERATOR = "OPERATOR";
    private static final String USER = "USER";

    /* ==================== Recorrido válido ==================== */

    @Test
    @DisplayName("el recorrido completo PAID → FORWARDED → SHIPPED → DELIVERED avanza y sella cada marca de tiempo")
    void recorridoCompletoDelPedido() {
        UUID productoId = sembrarEscenarioDeCompra();
        UUID compradorId = crearCompradorConSaldo();
        String admin = jwt.userToken(ADMIN);

        UUID pedidoId = pedidoPagado(compradorId, productoId, 1);
        assertThat(estadoDe(pedidoId)).as("pagar con saldo deja el pedido PAGADO").isEqualTo("PAID");

        assertThat(transicionAdmin(pedidoId, "forward", admin)).isEqualTo(200);
        assertThat(estadoDe(pedidoId)).isEqualTo("FORWARDED");

        assertThat(transicionAdmin(pedidoId, "ship", admin)).isEqualTo(200);
        assertThat(estadoDe(pedidoId)).isEqualTo("SHIPPED");

        assertThat(transicionAdmin(pedidoId, "deliver", admin)).isEqualTo(200);
        assertThat(estadoDe(pedidoId)).isEqualTo("DELIVERED");

        // Las marcas de tiempo son lo que ve el cliente en su seguimiento: si no se sellan, el pedido
        // aparece entregado sin fecha de entrega.
        Map<String, Object> fila = jdbcTemplate.queryForMap("SELECT placed_at, forwarded_at, shipped_at,"
                + " delivered_at, cancelled_at FROM customer_order WHERE id = ?", pedidoId);
        assertThat(fila.get("placed_at")).as("fecha del pedido").isNotNull();
        assertThat(fila.get("forwarded_at")).as("fecha de reenvío al proveedor").isNotNull();
        assertThat(fila.get("shipped_at")).as("fecha de salida").isNotNull();
        assertThat(fila.get("delivered_at")).as("fecha de entrega").isNotNull();
        assertThat(fila.get("cancelled_at")).as("un pedido entregado no puede tener fecha de cancelación")
                .isNull();
    }

    @Test
    @DisplayName("un pago externo deja el pedido PENDING y desde ahí sí se puede reenviar al proveedor")
    void pedidoPendienteDePagoSeReenvia() {
        UUID productoId = sembrarEscenarioDeCompra();
        UUID compradorId = crearCompradorConSaldo();

        UUID pedidoId = pedidoSinPagar(compradorId, productoId, 1);
        assertThat(estadoDe(pedidoId)).as("con tarjeta el pedido queda pendiente de pago").isEqualTo("PENDING");
        assertThat(saldoDe(compradorId)).as("un pago externo NO toca el monedero")
                .isEqualTo(SALDO_INICIAL_CENTS);

        assertThat(transicionAdmin(pedidoId, "forward", jwt.userToken(ADMIN))).isEqualTo(200);
        assertThat(estadoDe(pedidoId)).isEqualTo("FORWARDED");
    }

    @Test
    @DisplayName("AWAITING_PAYMENT también admite el reenvío al proveedor (estado forzado: no lo crea ningún endpoint)")
    void esperandoPagoSeReenvia() {
        UUID productoId = sembrarEscenarioDeCompra();
        UUID compradorId = crearCompradorConSaldo();
        UUID pedidoId = pedidoSinPagar(compradorId, productoId, 1);
        // Ningún flujo actual escribe AWAITING_PAYMENT, pero forwardOrder lo acepta explícitamente: si
        // alguien lo reintroduce (pasarela con confirmación diferida) esta casilla ya está cubierta.
        forzarEstado(pedidoId, "AWAITING_PAYMENT");

        assertThat(transicionAdmin(pedidoId, "forward", jwt.userToken(ADMIN))).isEqualTo(200);
        assertThat(estadoDe(pedidoId)).isEqualTo("FORWARDED");
    }

    /* ==================== Transiciones inválidas ==================== */

    @ParameterizedTest(name = "marcar en camino desde {0} se rechaza")
    @ValueSource(strings = {"PENDING", "AWAITING_PAYMENT", "PAID", "SHIPPED", "DELIVERED", "CANCELLED",
            "REFUNDED"})
    @DisplayName("«en camino» SOLO se admite desde FORWARDED: cualquier otro estado se rechaza con 422")
    void enviarSoloDesdeReenviado(String estadoOrigen) {
        UUID pedidoId = pedidoEnEstado(estadoOrigen);

        assertThat(transicionAdmin(pedidoId, "ship", jwt.userToken(ADMIN)))
                .as("marcar en camino un pedido en %s", estadoOrigen).isEqualTo(422);
        assertThat(estadoDe(pedidoId)).as("el estado no puede haberse movido").isEqualTo(estadoOrigen);
    }

    @ParameterizedTest(name = "entregar desde {0} se rechaza")
    @ValueSource(strings = {"PENDING", "AWAITING_PAYMENT", "PAID", "FORWARDED", "DELIVERED", "CANCELLED",
            "REFUNDED"})
    @DisplayName("«entregado» SOLO se admite desde SHIPPED: cualquier otro estado se rechaza con 422")
    void entregarSoloDesdeEnCamino(String estadoOrigen) {
        UUID pedidoId = pedidoEnEstado(estadoOrigen);

        assertThat(transicionAdmin(pedidoId, "deliver", jwt.userToken(ADMIN)))
                .as("entregar un pedido en %s", estadoOrigen).isEqualTo(422);
        assertThat(estadoDe(pedidoId)).as("el estado no puede haberse movido").isEqualTo(estadoOrigen);
    }

    @ParameterizedTest(name = "reenviar al proveedor desde {0} se rechaza con 422")
    @ValueSource(strings = {"SHIPPED", "DELIVERED", "CANCELLED", "REFUNDED"})
    @DisplayName("reenviar al proveedor un pedido que ya avanzó (o cancelado/reembolsado) se rechaza")
    void reenviarDesdeEstadoAvanzadoSeRechaza(String estadoOrigen) {
        UUID pedidoId = pedidoEnEstado(estadoOrigen);

        // Antes respondía 200 sin hacer nada, así que quien pulsaba el botón recibía lo que parecía una
        // confirmación de una operación que no había ocurrido — y sobre un pedido CANCELADO o REEMBOLSADO
        // eso es justo lo contrario de lo que debe contestar. Ahora rechaza, igual que ship/deliver/refund,
        // que siempre lanzaron 422 en estados imposibles; no había motivo para que este fuera la excepción.
        assertThat(transicionAdmin(pedidoId, "forward", jwt.userToken(ADMIN)))
                .as("reenviar desde %s", estadoOrigen).isEqualTo(422);
        assertThat(estadoDe(pedidoId)).as("el estado no puede haberse movido").isEqualTo(estadoOrigen);
    }

    @Test
    @DisplayName("reenviar al proveedor un pedido YA enviado es idempotente: responde 200 y no lo mueve")
    void reenviarUnPedidoYaEnviadoEsIdempotente() {
        UUID pedidoId = pedidoEnEstado("FORWARDED");

        // FORWARDED es el único estado avanzado que NO se rechaza: repetir la operación que ya se hizo es
        // idempotente a propósito, porque el panel puede repetir el clic o llegar dos peticiones a la vez.
        assertThat(transicionAdmin(pedidoId, "forward", jwt.userToken(ADMIN))).isEqualTo(200);
        assertThat(estadoDe(pedidoId)).isEqualTo("FORWARDED");
    }

    /* ==================== Estados terminales ==================== */

    @Test
    @DisplayName("cancelar un pedido ya cancelado es idempotente: 200 y sigue CANCELLED")
    void cancelarLoYaCancelado() {
        UUID pedidoId = pedidoEnEstado("PAID");
        String admin = jwt.userToken(ADMIN);

        assertThat(transicionAdmin(pedidoId, "cancel", admin)).isEqualTo(200);
        assertThat(estadoDe(pedidoId)).isEqualTo("CANCELLED");

        assertThat(transicionAdmin(pedidoId, "cancel", admin)).as("segunda cancelación").isEqualTo(200);
        assertThat(estadoDe(pedidoId)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("reembolsar un pedido ya reembolsado es idempotente: 200 y sigue REFUNDED")
    void reembolsarLoYaReembolsado() {
        UUID pedidoId = pedidoEnEstado("PAID");
        String admin = jwt.userToken(ADMIN);

        assertThat(transicionAdmin(pedidoId, "refund", admin)).isEqualTo(200);
        assertThat(estadoDe(pedidoId)).isEqualTo("REFUNDED");

        assertThat(transicionAdmin(pedidoId, "refund", admin)).as("segundo reembolso").isEqualTo(200);
        assertThat(estadoDe(pedidoId)).isEqualTo("REFUNDED");
    }

    @Test
    @DisplayName("no se puede reembolsar un pedido cancelado: 422 y el estado no cambia")
    void reembolsarUnCanceladoSeRechaza() {
        UUID pedidoId = pedidoEnEstado("CANCELLED");

        assertThat(transicionAdmin(pedidoId, "refund", jwt.userToken(ADMIN))).isEqualTo(422);
        assertThat(estadoDe(pedidoId)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("un pedido reembolsado todavía se puede cancelar y pasa a CANCELLED")
    void cancelarUnReembolsado() {
        UUID pedidoId = pedidoEnEstado("REFUNDED");

        // Asimetría deliberada del código: refund rechaza lo cancelado, pero cancel acepta lo reembolsado.
        // No hay doble abono (REFUNDED no cuenta como "estaba pagado"), y eso se comprueba en el bloque
        // de dinero; aquí solo se fija el estado resultante para que nadie lo cambie sin darse cuenta.
        assertThat(transicionAdmin(pedidoId, "cancel", jwt.userToken(ADMIN))).isEqualTo(200);
        assertThat(estadoDe(pedidoId)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("entregar un pedido ya entregado se rechaza con 422")
    void entregarLoYaEntregado() {
        UUID productoId = sembrarEscenarioDeCompra();
        UUID compradorId = crearCompradorConSaldo();
        String admin = jwt.userToken(ADMIN);
        UUID pedidoId = pedidoPagado(compradorId, productoId, 1);
        avanzarHasta(pedidoId, "DELIVERED", admin);

        assertThat(transicionAdmin(pedidoId, "deliver", admin)).isEqualTo(422);
        assertThat(estadoDe(pedidoId)).isEqualTo("DELIVERED");
    }

    /* ==================== Cancelación por el cliente ==================== */

    @Test
    @DisplayName("el cliente puede cancelar su pedido mientras está PAID (aún sin reenviar al proveedor)")
    void clienteCancelaMientrasEstaPagado() {
        UUID productoId = sembrarEscenarioDeCompra();
        UUID compradorId = crearCompradorConSaldo();
        UUID pedidoId = pedidoPagado(compradorId, productoId, 1);

        assertThat(cancelarComoCliente(compradorId, pedidoId, true)).isEqualTo(200);
        assertThat(estadoDe(pedidoId)).isEqualTo("CANCELLED");
    }

    @ParameterizedTest(name = "el cliente NO puede cancelar en {0}")
    @ValueSource(strings = {"PENDING", "AWAITING_PAYMENT", "FORWARDED", "SHIPPED", "DELIVERED", "CANCELLED",
            "REFUNDED"})
    @DisplayName("en cuanto el pedido sale de PAID la cancelación del cliente se rechaza con 422")
    void clienteNoCancelaFueraDePagado(String estadoOrigen) {
        UUID productoId = sembrarEscenarioDeCompra();
        UUID compradorId = crearCompradorConSaldo();
        UUID pedidoId = pedidoPagado(compradorId, productoId, 1);
        forzarEstado(pedidoId, estadoOrigen);

        assertThat(cancelarComoCliente(compradorId, pedidoId, true))
                .as("cancelar como cliente en %s", estadoOrigen).isEqualTo(422);
        assertThat(estadoDe(pedidoId)).isEqualTo(estadoOrigen);
    }

    @Test
    @DisplayName("el pedido de OTRO cliente responde 404 al cancelarlo y al consultarlo (nunca se filtra que existe)")
    void pedidoAjenoNoSeVeNiSeCancela() {
        UUID productoId = sembrarEscenarioDeCompra();
        UUID compradorId = crearCompradorConSaldo();
        UUID intrusoId = crearCompradorConSaldo();
        UUID pedidoId = pedidoPagado(compradorId, productoId, 1);

        assertThat(cancelarComoCliente(intrusoId, pedidoId, true))
                .as("un 403 confirmaría que el pedido existe; tiene que ser 404").isEqualTo(404);

        client.get().uri("/api/me/orders/" + pedidoId)
                .header("Authorization", bearer(tokenDe(intrusoId, USER))).exchange()
                .expectStatus().isNotFound();

        assertThat(estadoDe(pedidoId)).as("el pedido de la víctima queda intacto").isEqualTo("PAID");
    }

    /* ==================== Separación de poderes ==================== */

    @Test
    @DisplayName("el OPERATOR puede procesar el pedido: reenviar, marcar en camino y entregar")
    void operadorPuedeProcesar() {
        UUID productoId = sembrarEscenarioDeCompra();
        UUID compradorId = crearCompradorConSaldo();
        UUID operadorId = crearUsuario(OPERATOR);
        String operador = tokenDe(operadorId, OPERATOR);
        UUID pedidoId = pedidoPagado(compradorId, productoId, 1);

        assertThat(transicionAdmin(pedidoId, "forward", operador)).isEqualTo(200);
        assertThat(transicionAdmin(pedidoId, "ship", operador)).isEqualTo(200);
        assertThat(transicionAdmin(pedidoId, "deliver", operador)).isEqualTo(200);
        assertThat(estadoDe(pedidoId)).isEqualTo("DELIVERED");
    }

    @ParameterizedTest(name = "OPERATOR → POST {0} = 403")
    @CsvSource({
            "cancel, cancelar el pedido",
            "refund, reembolsar el pedido"
    })
    @DisplayName("el OPERATOR NO puede cancelar ni reembolsar: son operaciones con impacto financiero, solo de ADMIN")
    void operadorNoTocaElDinero(String accion, String descripcion) {
        UUID pedidoId = pedidoEnEstado("PAID");
        UUID operadorId = crearUsuario(OPERATOR);

        assertThat(transicionAdmin(pedidoId, accion, tokenDe(operadorId, OPERATOR)))
                .as("%s siendo OPERATOR", descripcion).isEqualTo(403);
        assertThat(estadoDe(pedidoId)).as("el pedido no puede haberse movido").isEqualTo("PAID");
        assertThat(saldoDe(compradorDelPedido(pedidoId))).as("y el monedero tampoco")
                .isEqualTo(SALDO_INICIAL_CENTS - totalEsperadoCents(1));
    }

    @Test
    @DisplayName("el OPERATOR tampoco puede reembolsar ni cancelar EN LOTE (el gate por URL dejaba pasar el masivo)")
    void operadorNoHaceLotesFinancieros() {
        UUID pedidoId = pedidoEnEstado("PAID");
        UUID operadorId = crearUsuario(OPERATOR);
        String operador = tokenDe(operadorId, OPERATOR);
        List<String> ids = List.of(pedidoId.toString());

        assertThat(postLote("/api/admin/orders/bulk-refund", operador, ids)).isEqualTo(403);
        assertThat(postLote("/api/admin/orders/bulk-cancel", operador, ids)).isEqualTo(403);
        assertThat(estadoDe(pedidoId)).isEqualTo("PAID");
    }

    @Test
    @DisplayName("el OPERATOR no puede crear ni importar pedidos, ni asomarse a los monederos")
    void operadorNoCreaPedidosNiVeMonederos() {
        UUID operadorId = crearUsuario(OPERATOR);
        String operador = tokenDe(operadorId, OPERATOR);

        assertThat(postVacio("/api/admin/orders", operador)).as("crear pedido manual").isEqualTo(403);
        assertThat(postVacio("/api/admin/orders/import", operador)).as("importar pedidos").isEqualTo(403);
        assertThat(postVacio("/api/admin/orders/demo", operador)).as("crear pedido de demo").isEqualTo(403);

        client.get().uri("/api/admin/wallets").header("Authorization", bearer(operador)).exchange()
                .expectStatus().isForbidden();
    }

    @Test
    @DisplayName("un cliente normal no puede ejecutar NINGUNA transición del panel de administración")
    void clienteNoOperaElPanel() {
        UUID pedidoId = pedidoEnEstado("PAID");
        UUID otroId = crearCompradorConSaldo();
        String cliente = tokenDe(otroId, USER);

        for (String accion : List.of("forward", "ship", "deliver", "cancel", "refund")) {
            assertThat(transicionAdmin(pedidoId, accion, cliente)).as("cliente → %s", accion).isEqualTo(403);
        }
        assertThat(estadoDe(pedidoId)).isEqualTo("PAID");
    }

    @Test
    @DisplayName("sin token, las transiciones del panel responden 401 y no 403")
    void anonimoNoOperaElPanel() {
        UUID pedidoId = pedidoEnEstado("PAID");

        for (String accion : List.of("forward", "ship", "deliver", "cancel", "refund")) {
            client.post().uri(ADMIN_ORDERS + pedidoId + "/" + accion).exchange()
                    .expectStatus().isUnauthorized();
        }
        assertThat(estadoDe(pedidoId)).isEqualTo("PAID");
    }

    /* ==================== Robustez de las entradas ==================== */

    @ParameterizedTest(name = "{0} sobre un pedido inexistente responde 404")
    @ValueSource(strings = {"forward", "ship", "deliver", "cancel", "refund"})
    @DisplayName("un pedido que no existe se responde 404, nunca un 500")
    void pedidoInexistenteDa404(String accion) {
        assertThat(transicionAdmin(UUID.randomUUID(), accion, jwt.userToken(ADMIN))).isEqualTo(404);
    }

    @ParameterizedTest(name = "{0} con un identificador mal formado responde 400")
    @ValueSource(strings = {"forward", "ship", "deliver", "cancel", "refund"})
    @DisplayName("un identificador que no es UUID se responde 400, nunca un 500")
    void identificadorInvalidoDa400(String accion) {
        int codigo = client.post().uri("/api/admin/orders/no-soy-un-uuid/" + accion)
                .header("Authorization", bearer(jwt.userToken(ADMIN))).exchange()
                .returnResult(Void.class).getStatus().value();
        assertThat(codigo).isEqualTo(400);
    }

    @Test
    @DisplayName("el detalle de un pedido inexistente responde 404 tanto al admin como al cliente")
    void detalleInexistenteDa404() {
        UUID compradorId = crearCompradorConSaldo();

        client.get().uri(ADMIN_ORDERS + UUID.randomUUID())
                .header("Authorization", bearer(jwt.userToken(ADMIN))).exchange()
                .expectStatus().isNotFound();
        client.get().uri("/api/me/orders/" + UUID.randomUUID())
                .header("Authorization", bearer(tokenDe(compradorId, USER))).exchange()
                .expectStatus().isNotFound();
    }

    /* ==================== Utilidades del bloque ==================== */

    /**
     * Pedido real (comprado y pagado con saldo) colocado en el estado pedido.
     *
     * <p>Hasta FORWARDED/SHIPPED/DELIVERED se llega por los endpoints reales, que es donde vive la
     * lógica; PENDING, AWAITING_PAYMENT, CANCELLED y REFUNDED se escriben en la base porque llegar a
     * ellos por HTTP implicaría ejecutar justo la operación que la prueba quiere examinar después.
     */
    private UUID pedidoEnEstado(String estado) {
        UUID productoId = sembrarEscenarioDeCompra();
        UUID compradorId = crearCompradorConSaldo();
        UUID pedidoId = pedidoPagado(compradorId, productoId, 1);
        if ("PAID".equals(estado)) {
            return pedidoId;
        }
        if (List.of("FORWARDED", "SHIPPED", "DELIVERED").contains(estado)) {
            avanzarHasta(pedidoId, estado, jwt.userToken(ADMIN));
            return pedidoId;
        }
        forzarEstado(pedidoId, estado);
        return pedidoId;
    }

    private UUID compradorDelPedido(UUID pedidoId) {
        return jdbcTemplate.queryForObject("SELECT user_id FROM customer_order WHERE id = ?", UUID.class,
                pedidoId);
    }

    private int postLote(String uri, String token, List<String> ids) {
        return client.post().uri(uri).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(ids)
                .exchange().returnResult(Void.class).getStatus().value();
    }

    private int postVacio(String uri, String token) {
        return client.post().uri(uri).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                .exchange().returnResult(Void.class).getStatus().value();
    }
}
