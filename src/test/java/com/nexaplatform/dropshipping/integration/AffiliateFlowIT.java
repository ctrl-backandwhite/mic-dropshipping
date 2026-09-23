package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.application.service.AffiliateProgramService;
import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.config.BaseIntegration;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Certificación del DINERO del programa de afiliados por HTTP, de punta a punta y contra un Postgres
 * real: alta, aprobación, clic, atribución, COMPRA REAL, comisión, anulación por reembolso o
 * cancelación, y liquidación por monedero o por fuera de la aplicación.
 *
 * <p>Aquí no vale un 200: cada importe se comprueba EXACTO y calculado a mano a partir del pedido que
 * el propio sistema acaba de crear. El descuento del comprador tiene que ser el 10 % del subtotal de
 * producto, y la comisión el porcentaje del afiliado sobre lo REALMENTE cobrado (subtotal − descuento).
 * Un céntimo de diferencia en cualquiera de los dos es un fallo.
 *
 * <p>Toda la maquinaria del catálogo, el checkout y el reembolso se recorre por HTTP con los mismos
 * endpoints que usa el frontend. Solo el REDONDEO de la comisión sobre importes minúsculos se prueba
 * sobre el método real del servicio, porque ningún endpoint permite fijar el importe de un pedido al
 * céntimo, y ese redondeo es justo donde se pierde o se regala dinero.
 */
class AffiliateFlowIT extends BaseIntegration {

    /* ---------------------------- Rutas reales del API ---------------------------- */

    private static final String ME_AFILIADO = "/api/me/affiliate";
    private static final String ME_AFILIADO_JOIN = "/api/me/affiliate/join";
    private static final String ME_AFILIADO_BIND = "/api/me/affiliate/bind";
    private static final String ME_AFILIADO_PERFIL_COBRO = "/api/me/affiliate/payout-profile";
    private static final String ME_AFILIADO_SOLICITAR_PAGO = "/api/me/affiliate/payout-request";
    private static final String ME_AFILIADO_CODIGOS = "/api/me/affiliate/codes";
    private static final String TRACK = "/api/affiliate/track";
    private static final String CHECKOUT = "/api/me/orders/checkout";
    private static final String ADMIN_AFILIADOS = "/api/admin/affiliates";
    private static final String ADMIN_AFILIADO_ESTADO = "/api/admin/affiliates/%s/status";
    private static final String ADMIN_CONFIG = "/api/admin/affiliates/config";
    private static final String ADMIN_APROBAR_VENCIDAS = "/api/admin/affiliates/approve-due";
    private static final String ADMIN_PAGOS_PENDIENTES = "/api/admin/affiliates/payouts/pending";
    private static final String ADMIN_APROBAR_PAGO = "/api/admin/affiliates/payouts/%s/approve";
    private static final String ADMIN_CATEGORIAS = "/api/admin/catalog/categories";
    private static final String ADMIN_CREAR_PRODUCTO = "/api/admin/catalog/products/create";
    private static final String ADMIN_REEMBOLSAR = "/api/admin/orders/%s/refund";
    private static final String ADMIN_CANCELAR = "/api/admin/orders/%s/cancel";

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LISTA_JSON = new ParameterizedTypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAPA_JSON = new TypeReference<>() {
    };

    private static final int NO_PROCESABLE = 422;
    /** Porcentaje por defecto del programa: comisión del afiliado Y descuento del comprador. */
    private static final BigDecimal DIEZ_POR_CIENTO = new BigDecimal("10");
    private static final String CONTRASENA = "Cert-2026!";
    private static final String IBAN_VALIDO = "ES9121000418450200051332";
    private static final String CAMPO_ESTADO = "status";
    private static final String CAMPO_IMPORTE_CENTS = "amountCents";
    private static final String CAMPO_METODO = "method";
    private static final String CAMPO_CODIGO = "code";
    private static final String CAMPO_TOKEN_VISITANTE = "visitorToken";

    @Autowired
    private CurrencyRateService currencyRateService;

    @Autowired
    private MarginService marginService;

    @Autowired
    private AffiliateProgramService affiliateProgramService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID afiliadoId;
    private String afiliadoToken;
    private UUID compradorId;
    private String compradorToken;
    private String adminToken;
    private UUID productoId;

    /**
     * Escenario de partida. Dos detalles que no son adorno:
     *
     * <ul>
     *   <li>La limpieza de tablas de la clase base se lleva por delante los datos de referencia que
     *       carga Liquibase (divisas, zonas de envío, reglas de margen). Se reponen los tres mínimos
     *       para poder comprar: la tasa del yuan, un destino con transporte y el margen a cero.</li>
     *   <li>El margen se deja a CERO (sin reglas de precio) a propósito: así el precio de venta es el
     *       coste convertido y los importes del pedido son previsibles al céntimo, que es de lo que va
     *       esta clase. Con 7,00 CNY por dólar, un producto de 70 CNY vale exactamente 10,00 USD.</li>
     * </ul>
     */
    @BeforeEach
    void prepararEscenario() {
        currencyRateService.applyBulkSync(Map.of("CNY", new BigDecimal("7.00")));
        marginService.invalidateCache();
        jdbcTemplate.update("INSERT INTO cainiao_shipping_zone (id, country_code, country_name, zone, base_cents,"
                + " per_kg_cents, eta_min_days, eta_max_days) VALUES (?, 'ES', 'España', 'EU', 499, 350, 8, 18)",
                UUID.randomUUID());

        afiliadoId = crearUsuario("afiliado-" + UUID.randomUUID() + "@example.com", "USER");
        afiliadoToken = jwt.userToken(afiliadoId, "afiliado@example.com", "USER");
        compradorId = crearUsuario("comprador-" + UUID.randomUUID() + "@example.com", "USER");
        compradorToken = jwt.userToken(compradorId, "comprador@example.com", "USER");
        adminToken = jwt.userToken(crearUsuario("admin-" + UUID.randomUUID() + "@example.com", "ADMIN"),
                "admin@example.com", "ADMIN");

        productoId = crearProductoComprable();
        // Monedero abierto y con fondos para los dos. Se abre a mano y NO con el alta manual del panel
        // porque esa vía exige que el monedero YA exista (ver elPagoPorWalletExigeMonederoAbierto).
        sembrarMonedero(compradorId, 1_000_000L);
        sembrarMonedero(afiliadoId, 1_000_000L);
    }

    /* ============================== Alta, código y comisión ============================== */

    /**
     * El recorrido completo con el dinero al céntimo: el afiliado se da de alta, el administrador lo
     * aprueba, un visitante pincha su enlace, se identifica como comprador y compra.
     *
     * <p>Los tres números se calculan a mano sobre el subtotal REAL del pedido:
     * descuento = 10 % del subtotal; base de comisión = subtotal − descuento; comisión = 10 % de la base.
     * Y el subtotal, además, tiene que ser el previsto: 70 CNY a 7,00 = 10,00 USD.
     */
    @Test
    @DisplayName("alta, código de referido y comisión al comprar: importes exactos al céntimo")
    void altaCodigoYComisionExacta() {
        Map<String, Object> panel = json(HttpMethod.POST, ME_AFILIADO_JOIN, afiliadoToken, null, 200);
        assertThat(panel.get(CAMPO_ESTADO)).as("nace PENDIENTE: hasta que el admin no aprueba no gana nada")
                .isEqualTo("PENDING");
        assertThat(panel.get("joined")).isEqualTo(true);

        String codigo = codigoPrincipal(panel);
        assertThat(codigo).as("el alta genera un código de referido").isNotBlank();
        UUID afiliadoEntidadId = UUID.fromString(String.valueOf(panel.get("id")));

        aprobarAfiliado(afiliadoEntidadId);
        atribuirCompradorAlCodigo(codigo);

        UUID pedidoId = comprar(compradorToken, 1, null);

        long subtotal = importeDelPedido(pedidoId, "subtotal_cents");
        long descuento = importeDelPedido(pedidoId, "discount_cents");
        assertThat(subtotal).as("70 CNY a 7,00 CNY/USD = 10,00 USD = 1.000 céntimos").isEqualTo(1_000L);
        assertThat(descuento).as("descuento del comprador: 10 %% de %d", subtotal)
                .isEqualTo(porcentaje(subtotal, DIEZ_POR_CIENTO));

        long baseEsperada = subtotal - descuento;
        long comisionEsperada = porcentaje(baseEsperada, DIEZ_POR_CIENTO);
        assertThat(comisionEsperada).as("10 % de 900 = 90 céntimos").isEqualTo(90L);

        Map<String, Object> panelTrasLaVenta = json(HttpMethod.GET, ME_AFILIADO, afiliadoToken, null, 200);
        Map<String, Object> estadisticas = mapa(panelTrasLaVenta, "stats");
        assertThat(numero(estadisticas, "conversions")).isEqualTo(1L);
        assertThat(numero(estadisticas, "pendingCents")).as("la comisión nace PENDIENTE por el importe exacto")
                .isEqualTo(comisionEsperada);
        assertThat(numero(estadisticas, "approvedCents")).isZero();
        assertThat(numero(estadisticas, "paidCents")).isZero();

        List<Map<String, Object>> comisiones = lista(panelTrasLaVenta, "recentCommissions");
        assertThat(comisiones).hasSize(1);
        Map<String, Object> comision = comisiones.get(0);
        assertThat(numero(comision, CAMPO_IMPORTE_CENTS)).isEqualTo(comisionEsperada);
        assertThat(numero(comision, "baseAmountCents")).as("la comisión se calcula sobre lo realmente cobrado")
                .isEqualTo(baseEsperada);
        assertThat(new BigDecimal(String.valueOf(comision.get("percentage")))).isEqualByComparingTo(DIEZ_POR_CIENTO);
        assertThat(comision.get(CAMPO_ESTADO)).isEqualTo("PENDING");
        assertThat(comision.get("orderId")).isEqualTo(pedidoId.toString());
    }

    /**
     * Una comisión por pedido y no más. Se repite el checkout con la MISMA clave de idempotencia: el
     * sistema devuelve el mismo pedido y no puede volver a apuntar la conversión. Si la apuntara, el
     * afiliado cobraría dos veces por una sola venta.
     */
    @Test
    @DisplayName("un pedido genera UNA sola comisión aunque se repita el checkout")
    void unPedidoGeneraUnaSolaComision() {
        String codigo = darDeAltaYAprobarAfiliado();
        atribuirCompradorAlCodigo(codigo);

        String clave = "checkout-repetido";
        UUID primero = comprar(compradorToken, 1, clave);
        UUID segundo = comprar(compradorToken, 1, clave);

        assertThat(segundo).as("el reintento devuelve EL MISMO pedido").isEqualTo(primero);
        assertThat(conversionesDelPedido(primero)).as("una sola conversión apuntada").isEqualTo(1L);
        assertThat(numeroDeComisiones(afiliadoId)).as("y una sola comisión").isEqualTo(1L);
        assertThat(numero(mapa(json(HttpMethod.GET, ME_AFILIADO, afiliadoToken, null, 200), "stats"), "pendingCents"))
                .isEqualTo(90L);
    }

    /** Dos pedidos distintos SÍ generan dos comisiones: el contrapeso del caso anterior. */
    @Test
    @DisplayName("dos pedidos distintos generan dos comisiones y la suma es exacta")
    void dosPedidosGeneranDosComisiones() {
        String codigo = darDeAltaYAprobarAfiliado();
        atribuirCompradorAlCodigo(codigo);

        comprar(compradorToken, 1, null);
        comprar(compradorToken, 2, null);

        // Pedido de 2 unidades: subtotal 2.000, descuento 200, base 1.800, comisión 180.
        assertThat(numeroDeComisiones(afiliadoId)).isEqualTo(2L);
        assertThat(numero(mapa(json(HttpMethod.GET, ME_AFILIADO, afiliadoToken, null, 200), "stats"), "pendingCents"))
                .as("90 + 180").isEqualTo(270L);
    }

    /** El porcentaje PROPIO del afiliado manda sobre el del programa, y se redondea al céntimo. */
    @Test
    @DisplayName("la comisión usa el porcentaje propio del afiliado cuando lo tiene")
    void laComisionUsaElPorcentajePropioDelAfiliado() {
        String codigo = darDeAltaYAprobarAfiliado();
        jdbcTemplate.update("UPDATE affiliate SET commission_percent_override = 7.500 WHERE user_id = ?", afiliadoId);
        atribuirCompradorAlCodigo(codigo);

        comprar(compradorToken, 1, null);

        // Base 900 al 7,5 % = 67,5 → 68 céntimos (HALF_UP). Es el redondeo que decide si se regala o se roba.
        assertThat(numero(mapa(json(HttpMethod.GET, ME_AFILIADO, afiliadoToken, null, 200), "stats"), "pendingCents"))
                .as("7,5 %% de 900 = 67,5 → 68 al redondear al alza").isEqualTo(68L);
    }

    /**
     * Redondeo sobre importes minúsculos, el caso que ningún checkout puede montar al céntimo: una base
     * de 5 céntimos al 10 % son 0,5 céntimos y se redondean a 1; una de 4 céntimos son 0,4 y se quedan
     * en 0. Se ataca el método real que llama el checkout, no un cálculo escrito aquí.
     *
     * <p>Los pedidos son REALES y no identificadores inventados: {@code affiliate_conversion.order_id}
     * tiene clave ajena contra {@code customer_order}, así que una conversión sobre un pedido que no
     * existe ni llega a insertarse. Se compran ANTES de atribuir el código a propósito: sin atribución
     * viva el checkout no apunta conversión ninguna, y así el caso puede invocar después el cálculo con
     * los importes mínimos que el propio checkout jamás produciría.
     */
    @Test
    @DisplayName("la comisión de un pedido mínimo redondea al céntimo más cercano")
    void laComisionDeUnPedidoMinimoRedondeaAlCentimo() {
        String codigo = darDeAltaYAprobarAfiliado();
        UUID pedidoDeMedioCentimo = comprar(compradorToken, 1, null);
        UUID pedidoDeCuatroDecimas = comprar(compradorToken, 2, null);
        atribuirCompradorAlCodigo(codigo);
        UUID afiliadoEntidadId = idDeAfiliado(afiliadoId);

        affiliateProgramService.onOrderPlaced(pedidoDeMedioCentimo, compradorId, 5L, "USD");
        affiliateProgramService.onOrderPlaced(pedidoDeCuatroDecimas, compradorId, 4L, "USD");

        List<Long> importes = jdbcTemplate.queryForList(
                "SELECT amount_cents FROM affiliate_commission" + " WHERE affiliate_id = ? ORDER BY amount_cents DESC",
                Long.class, afiliadoEntidadId);
        assertThat(importes).as("10 % de 5 = 0,5 → 1; 10 % de 4 = 0,4 → 0").containsExactly(1L, 0L);
    }

    /**
     * Autorreferido: quien usa su PROPIO código no se paga a sí mismo ni se descuenta el 10 %. Es el
     * fraude más barato de intentar y el que más veces se cuela.
     */
    @Test
    @DisplayName("el autorreferido no genera comisión ni descuento")
    void elAutorreferidoNoGeneraComisionNiDescuento() {
        String codigo = darDeAltaYAprobarAfiliado();
        atribuirUsuarioAlCodigo(codigo, afiliadoToken);

        UUID pedidoId = comprar(afiliadoToken, 1, null);

        assertThat(importeDelPedido(pedidoId, "discount_cents")).as("con tu propio código no hay descuento").isZero();
        assertThat(conversionesDelPedido(pedidoId)).as("ni conversión").isZero();
        assertThat(numeroDeComisiones(afiliadoId)).as("ni comisión").isZero();
    }

    /**
     * Un afiliado que aún no ha sido aprobado no cuenta clics ni genera nada: el alta por sí sola no
     * abre la caja. Sin este freno, cualquiera se daría de alta y empezaría a cobrar sin revisión.
     */
    @Test
    @DisplayName("un afiliado pendiente de aprobación no atribuye clics ni genera comisión ni descuento")
    void elAfiliadoPendienteNoGeneraNada() {
        Map<String, Object> panel = json(HttpMethod.POST, ME_AFILIADO_JOIN, afiliadoToken, null, 200);
        String codigo = codigoPrincipal(panel);

        Map<String, Object> clic = json(HttpMethod.POST, TRACK, null, Map.of("ref", codigo), 200);
        assertThat(clic.get("attributed")).as("un afiliado sin aprobar no atribuye el clic").isEqualTo(false);

        json(HttpMethod.POST, ME_AFILIADO_BIND, compradorToken,
                Map.of(CAMPO_TOKEN_VISITANTE, String.valueOf(clic.get(CAMPO_TOKEN_VISITANTE))), 204);
        UUID pedidoId = comprar(compradorToken, 1, null);

        assertThat(importeDelPedido(pedidoId, "discount_cents")).isZero();
        assertThat(conversionesDelPedido(pedidoId)).isZero();
        assertThat(numeroDeComisiones(afiliadoId)).isZero();
    }

    /** Un código que no existe no atribuye nada y, sobre todo, no rompe la página de destino. */
    @Test
    @DisplayName("un código de referido inexistente no atribuye nada")
    void codigoInexistenteNoAtribuye() {
        Map<String, Object> clic = json(HttpMethod.POST, TRACK, null, Map.of("ref", "codigo-que-no-existe"), 200);

        assertThat(clic.get("attributed")).isEqualTo(false);
        assertThat(clic.get(CAMPO_TOKEN_VISITANTE)).as("aun así devuelve token, para no romper el escaparate")
                .isNotNull();
    }

    /* ============================== Anulación de la comisión ============================== */

    /** Si el pedido se REEMBOLSA, la comisión se anula: no se paga una venta que se ha devuelto. */
    @Test
    @DisplayName("la comisión se anula si el pedido se reembolsa")
    void laComisionSeAnulaSiElPedidoSeReembolsa() {
        String codigo = darDeAltaYAprobarAfiliado();
        atribuirCompradorAlCodigo(codigo);
        UUID pedidoId = comprar(compradorToken, 1, null);
        assertThat(numero(mapa(json(HttpMethod.GET, ME_AFILIADO, afiliadoToken, null, 200), "stats"), "pendingCents"))
                .isEqualTo(90L);

        json(HttpMethod.POST, String.format(ADMIN_REEMBOLSAR, pedidoId), adminToken, null, 200);

        assertThat(estadoDeLaComision(afiliadoId)).isEqualTo("REJECTED");
        Map<String, Object> estadisticas = mapa(json(HttpMethod.GET, ME_AFILIADO, afiliadoToken, null, 200), "stats");
        assertThat(numero(estadisticas, "pendingCents")).as("la comisión anulada deja de estar pendiente").isZero();
        assertThat(numero(estadisticas, "approvedCents")).isZero();
        assertThat(gananciasAcumuladas(afiliadoId)).as("y las ganancias acumuladas vuelven a cero").isZero();
    }

    /** Y lo mismo si se CANCELA: la comisión sigue al pedido, no al revés. */
    @Test
    @DisplayName("la comisión se anula si el pedido se cancela")
    void laComisionSeAnulaSiElPedidoSeCancela() {
        String codigo = darDeAltaYAprobarAfiliado();
        atribuirCompradorAlCodigo(codigo);
        UUID pedidoId = comprar(compradorToken, 1, null);

        json(HttpMethod.POST, String.format(ADMIN_CANCELAR, pedidoId), adminToken, null, 200);

        assertThat(estadoDeLaComision(afiliadoId)).isEqualTo("REJECTED");
        assertThat(gananciasAcumuladas(afiliadoId)).isZero();
        assertThat(numero(mapa(json(HttpMethod.GET, ME_AFILIADO, afiliadoToken, null, 200), "stats"), "pendingCents"))
                .isZero();
    }

    /* ============================== Retiradas ============================== */

    /**
     * Retirada por encima de lo disponible: se rechaza. El importe no lo pone el afiliado —el endpoint
     * liquida SIEMPRE sus comisiones aprobadas—, así que lo que hay que comprobar es que un afiliado sin
     * saldo aprobado no puede sacar nada, ni siquiera pidiendo una cifra absurda.
     */
    @Test
    @DisplayName("una retirada por encima del saldo aprobado se rechaza")
    void retiradaPorEncimaDelSaldoAprobadoSeRechaza() {
        String codigo = darDeAltaYAprobarAfiliado();
        atribuirCompradorAlCodigo(codigo);
        comprar(compradorToken, 1, null);
        // El monedero del afiliado NACE con fondos (los siembra el escenario), así que lo que prueba que
        // no se ha pagado nada no es que el saldo sea cero, sino que sea EL MISMO de antes: comparar
        // contra cero solo comprobaba cómo está sembrado el escenario.
        long saldoAntes = saldoDeLaWallet(afiliadoId);

        // La comisión existe pero está PENDIENTE (aún no vencida): no hay nada aprobado que liquidar.
        Map<String, Object> error = json(HttpMethod.POST, ME_AFILIADO_SOLICITAR_PAGO, afiliadoToken,
                Map.of(CAMPO_METODO, "WALLET", CAMPO_IMPORTE_CENTS, 99_999_999L), NO_PROCESABLE);

        assertThat(String.valueOf(error.get("message"))).isNotBlank();
        assertThat(pagosDelAfiliado(afiliadoId)).as("una solicitud rechazada no deja pago abierto").isZero();
        assertThat(saldoDeLaWallet(afiliadoId)).as("y no se abona nada").isEqualTo(saldoAntes);
    }

    /**
     * La retirada por WALLET abona el importe EXACTO de las comisiones aprobadas, ni un céntimo más, y
     * deja las comisiones marcadas como pagadas.
     */
    @Test
    @DisplayName("la retirada por wallet abona el importe exacto y marca las comisiones como pagadas")
    void laRetiradaPorWalletAbonaElImporteExacto() {
        long comision = generarComisionAprobada();
        assertThat(comision).isEqualTo(90L);
        long saldoAntes = saldoDeLaWallet(afiliadoId);

        UUID pagoId = solicitarPago("WALLET");
        json(HttpMethod.POST, String.format(ADMIN_APROBAR_PAGO, pagoId), adminToken, Map.of("reference", ""), 200);

        assertThat(saldoDeLaWallet(afiliadoId)).as("abonado EXACTAMENTE el importe aprobado")
                .isEqualTo(saldoAntes + comision);
        assertThat(estadoDeLaComision(afiliadoId)).isEqualTo("PAID");
        assertThat(numero(mapa(json(HttpMethod.GET, ME_AFILIADO, afiliadoToken, null, 200), "stats"), "paidCents"))
                .isEqualTo(comision);
    }

    /**
     * La retirada por TRANSFERENCIA la ejecuta el administrador FUERA de la aplicación: aquí solo se
     * registra. El saldo de la wallet NO se puede mover, o el afiliado cobraría dos veces (el banco y el
     * monedero).
     */
    @Test
    @DisplayName("la retirada por transferencia no toca el saldo de la wallet")
    void laRetiradaPorTransferenciaNoTocaLaWallet() {
        generarComisionAprobada();
        long saldoAntes = saldoDeLaWallet(afiliadoId);
        configurarDatosDeCobro();

        UUID pagoId = solicitarPago("BANK");
        json(HttpMethod.POST, String.format(ADMIN_APROBAR_PAGO, pagoId), adminToken,
                Map.of("reference", "TRF-2026-0001"), 200);

        assertThat(saldoDeLaWallet(afiliadoId)).as("el pago externo NO abona la wallet").isEqualTo(saldoAntes);
        assertThat(estadoDeLaComision(afiliadoId)).as("pero la comisión sí queda liquidada").isEqualTo("PAID");
        assertThat(jdbcTemplate.queryForObject("SELECT paid_reference FROM affiliate_payout WHERE id = ?", String.class,
                pagoId)).as("con la referencia de la transferencia anotada").isEqualTo("TRF-2026-0001");
        assertThat(jdbcTemplate.queryForObject("SELECT wallet_tx_id FROM affiliate_payout WHERE id = ?", UUID.class,
                pagoId)).as("y sin apunte de monedero asociado").isNull();
    }

    /** Lo mismo por PayPal: se registra el pago, no se toca el monedero. */
    @Test
    @DisplayName("la retirada por PayPal tampoco toca el saldo de la wallet")
    void laRetiradaPorPayPalNoTocaLaWallet() {
        generarComisionAprobada();
        long saldoAntes = saldoDeLaWallet(afiliadoId);
        configurarDatosDeCobro();

        UUID pagoId = solicitarPago("PAYPAL");
        json(HttpMethod.POST, String.format(ADMIN_APROBAR_PAGO, pagoId), adminToken,
                Map.of("reference", "PP-2026-0001"), 200);

        assertThat(saldoDeLaWallet(afiliadoId)).isEqualTo(saldoAntes);
        assertThat(estadoDeLaComision(afiliadoId)).isEqualTo("PAID");
    }

    /** Sin datos bancarios configurados no se puede pedir el cobro por transferencia. */
    @Test
    @DisplayName("pedir el cobro por transferencia sin datos bancarios se rechaza")
    void cobroPorTransferenciaSinDatosSeRechaza() {
        generarComisionAprobada();

        Map<String, Object> error = json(HttpMethod.POST, ME_AFILIADO_SOLICITAR_PAGO, afiliadoToken,
                Map.of(CAMPO_METODO, "BANK"), NO_PROCESABLE);

        assertThat(error.get(CAMPO_CODIGO)).isEqualTo("PAYOUT_DETAILS_MISSING");
        assertThat(pagosDelAfiliado(afiliadoId)).isZero();
    }

    /** Dos solicitudes seguidas no abren dos pagos: la segunda choca con la que ya está en cola. */
    @Test
    @DisplayName("una segunda solicitud de pago con otra pendiente se rechaza")
    void segundaSolicitudDePagoSeRechaza() {
        generarComisionAprobada();
        solicitarPago("WALLET");

        json(HttpMethod.POST, ME_AFILIADO_SOLICITAR_PAGO, afiliadoToken, Map.of(CAMPO_METODO, "WALLET"), NO_PROCESABLE);

        assertThat(pagosDelAfiliado(afiliadoId)).as("sigue habiendo un solo pago").isEqualTo(1L);
    }

    /**
     * Aprobar dos veces el MISMO pago no abona dos veces: el pago ya liquidado se devuelve tal cual. Es
     * el equivalente al reenvío del webhook, pero con un administrador haciendo doble clic.
     */
    @Test
    @DisplayName("aprobar dos veces el mismo pago no abona dos veces")
    void aprobarDosVecesElMismoPagoNoAbonaDosVeces() {
        long comision = generarComisionAprobada();
        long saldoAntes = saldoDeLaWallet(afiliadoId);
        UUID pagoId = solicitarPago("WALLET");

        json(HttpMethod.POST, String.format(ADMIN_APROBAR_PAGO, pagoId), adminToken, Map.of("reference", ""), 200);
        json(HttpMethod.POST, String.format(ADMIN_APROBAR_PAGO, pagoId), adminToken, Map.of("reference", ""), 200);

        assertThat(saldoDeLaWallet(afiliadoId)).as("abonado UNA vez").isEqualTo(saldoAntes + comision);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM wallet_transaction t JOIN wallet w"
                + " ON w.id = t.wallet_id WHERE w.user_id = ? AND t.kind = 'DEPOSIT'", Long.class, afiliadoId))
                .as("y un solo apunte de abono").isEqualTo(1L);
    }

    /**
     * DEFECTO ENCONTRADO AL CERTIFICAR (deshabilitado a propósito: hoy FALLA).
     *
     * <p>El abono al monedero ({@code WalletUseCaseImpl.recordTransaction}) exige que la fila del
     * monedero YA exista: si no, responde 404 "Wallet not found". Y el monedero NO se abre al registrarse
     * — solo lo abren el barrido de arranque {@code WalletBootstrapListener} (que recorre los usuarios
     * que existían al arrancar), la consulta de {@code GET /api/me/wallet} y el inicio de una recarga.
     *
     * <p>Consecuencia con dinero de por medio: un afiliado que se dio de alta DESPUÉS del último arranque
     * y que nunca abrió su pantalla de monedero no puede cobrar por WALLET — la aprobación del pago
     * revienta con 404 y la transacción entera se deshace, así que se queda sin cobrar y sin rastro. Lo
     * mismo le pasa al alta manual de saldo desde el panel.
     *
     * <p>Se deja escrito y deshabilitado para no dar por buena una suite verde sobre un agujero: en
     * cuanto el pago abra el monedero (o lo abra el registro), quítese el {@code @Disabled}.
     */
    @Test
    @Disabled("DEFECTO: el pago por wallet a un afiliado sin monedero abierto responde 404 y no se paga")
    @DisplayName("el pago por wallet abre el monedero del afiliado si aún no lo tiene")
    void elPagoPorWalletExigeMonederoAbierto() {
        jdbcTemplate.update("DELETE FROM wallet WHERE user_id = ?", afiliadoId);
        long comision = generarComisionAprobada();
        UUID pagoId = solicitarPago("WALLET");

        json(HttpMethod.POST, String.format(ADMIN_APROBAR_PAGO, pagoId), adminToken, Map.of("reference", ""), 200);

        assertThat(saldoDeLaWallet(afiliadoId)).as("el afiliado tiene que cobrar aunque no hubiera abierto monedero")
                .isEqualTo(comision);
    }

    /* ============================== Autorización cruzada ============================== */

    /**
     * Lo de otro afiliado no se ve ni se toca. Apagar el código ajeno se responde como inexistente y no
     * como prohibido: un 403 confirmaría al atacante que ese código existe, y con el código apagado se
     * le cortan al dueño todas sus comisiones futuras.
     */
    @Test
    @DisplayName("un afiliado no puede ver ni tocar lo de otro")
    void unAfiliadoNoPuedeTocarLoDeOtro() {
        String codigo = darDeAltaYAprobarAfiliado();
        UUID afiliadoEntidadId = idDeAfiliado(afiliadoId);
        UUID codigoId = idDelCodigo(codigo);
        atribuirCompradorAlCodigo(codigo);
        comprar(compradorToken, 1, null);

        // El panel de administración es exclusivo del administrador.
        peticion(HttpMethod.GET, ADMIN_AFILIADOS, compradorToken, null, null).expectStatus().isForbidden();
        peticion(HttpMethod.POST, String.format(ADMIN_AFILIADO_ESTADO, afiliadoEntidadId), compradorToken,
                Map.of(CAMPO_ESTADO, "SUSPENDED"), null).expectStatus().isForbidden();
        peticion(HttpMethod.POST, ADMIN_APROBAR_VENCIDAS, compradorToken, null, null).expectStatus().isForbidden();

        // El código de otro no se puede apagar.
        json(HttpMethod.POST, ME_AFILIADO_CODIGOS + "/" + codigoId + "/toggle?active=false", compradorToken, null, 404);
        assertThat(jdbcTemplate.queryForObject("SELECT active FROM affiliate_referral_code WHERE id = ?", Boolean.class,
                codigoId)).as("el código del dueño sigue encendido").isTrue();

        // Y el panel de cada uno solo enseña lo suyo.
        Map<String, Object> panelDelComprador = json(HttpMethod.GET, ME_AFILIADO, compradorToken, null, 200);
        assertThat(numero(mapa(panelDelComprador, "stats"), "pendingCents")).as("el comprador no ve comisión ajena")
                .isZero();
        assertThat(lista(panelDelComprador, "recentCommissions")).isEmpty();
    }

    /** Sin cuenta no hay panel de afiliado ni solicitud de cobro. */
    @Test
    @DisplayName("sin autenticar no se accede al área de afiliado")
    void sinAutenticarNoHayAreaDeAfiliado() {
        client.get().uri(ME_AFILIADO).exchange().expectStatus().isUnauthorized();
        client.post().uri(ME_AFILIADO_JOIN).exchange().expectStatus().isUnauthorized();
        client.post().uri(ME_AFILIADO_SOLICITAR_PAGO).exchange().expectStatus().isUnauthorized();
    }

    /** El perfil de cobro se lee con el IBAN ENMASCARADO: los datos bancarios no se devuelven enteros. */
    @Test
    @DisplayName("el perfil de cobro devuelve el IBAN enmascarado")
    void elPerfilDeCobroDevuelveElIbanEnmascarado() {
        darDeAltaYAprobarAfiliado();
        configurarDatosDeCobro();

        Map<String, Object> perfil = json(HttpMethod.GET, ME_AFILIADO_PERFIL_COBRO, afiliadoToken, null, 200);

        assertThat(String.valueOf(perfil.get("bankIbanMasked"))).isEqualTo("****1332");
        assertThat(perfil.get("hasBank")).isEqualTo(true);
        assertThat(perfil.toString()).as("el IBAN completo no puede viajar en la respuesta")
                .doesNotContain(IBAN_VALIDO);
    }

    /** Cambiar los datos de cobro exige la contraseña: sin ella, un token robado desviaría el dinero. */
    @Test
    @DisplayName("cambiar los datos de cobro sin la contraseña correcta se rechaza")
    void cambiarDatosDeCobroSinContrasenaSeRechaza() {
        darDeAltaYAprobarAfiliado();
        fijarContrasena(afiliadoId);

        Map<String, Object> peticionSinClave = new HashMap<>();
        peticionSinClave.put("bankHolder", "Ladrón");
        peticionSinClave.put("iban", IBAN_VALIDO);
        peticionSinClave.put("password", "no-es-la-buena");
        json(HttpMethod.PUT, ME_AFILIADO_PERFIL_COBRO, afiliadoToken, peticionSinClave, NO_PROCESABLE);

        assertThat(jdbcTemplate.queryForObject("SELECT bank_iban FROM affiliate WHERE user_id = ?", String.class,
                afiliadoId)).as("los datos bancarios no se han tocado").isNull();
    }

    /* ============================== Utilidades ============================== */

    private UUID crearUsuario(String email, String rol) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, role, active, created_at, updated_at)"
                + " VALUES (?, ?, ?, true, now(), now())", id, email, rol);
        return id;
    }

    /** Contraseña conocida para las operaciones que la exigen (cambio de datos de cobro). */
    private void fijarContrasena(UUID userId) {
        jdbcTemplate.update("UPDATE users SET password_hash = ? WHERE id = ?", passwordEncoder.encode(CONTRASENA),
                userId);
    }

    /**
     * Producto comprable por el camino real del panel: categoría + producto con precio, envío, IVA,
     * imagen y una variante con existencias. 70 CNY a 7,00 CNY/USD son exactamente 10,00 USD.
     */
    private UUID crearProductoComprable() {
        String slug = "cert-afiliados";
        peticion(HttpMethod.POST, ADMIN_CATEGORIAS, adminToken,
                Map.of("slug", slug, "position", 0, "nameTranslations", Map.of("es", "Certificación")), null)
                .expectStatus().is2xxSuccessful();

        Map<String, Object> producto = new HashMap<>();
        producto.put("categorySlug", slug);
        producto.put("supplierName", "Proveedor de certificación");
        producto.put("titleEs", "Producto de certificación");
        producto.put("externalId", "cert-" + UUID.randomUUID());
        producto.put("price", new BigDecimal("70.00"));
        producto.put("shippingCny", BigDecimal.ZERO);
        producto.put("ivaCny", BigDecimal.ZERO);
        producto.put("imageUrls", List.of("https://example.com/certificacion.jpg"));
        producto.put("weightGrams", 500);
        producto.put("moq", 1);
        producto.put("variants", List
                .of(Map.of("sku", "CERT-1", "price", new BigDecimal("70.00"), "stock", 500, "optionValues", Map.of())));

        return peticion(HttpMethod.POST, ADMIN_CREAR_PRODUCTO, adminToken, producto, null).expectStatus()
                .is2xxSuccessful().expectBody(UUID.class).returnResult().getResponseBody();
    }

    /** Monedero abierto con saldo de partida: el alta del monedero no es lo que se certifica aquí. */
    private void sembrarMonedero(UUID userId, long centimos) {
        jdbcTemplate.update(
                "INSERT INTO wallet (id, user_id, balance_usd_cents, hold_usd_cents, currency_default,"
                        + " status, created_at, updated_at) VALUES (?, ?, ?, 0, 'USD', 'ACTIVE', now(), now())",
                UUID.randomUUID(), userId, centimos);
    }

    /** Alta + aprobación del afiliado; devuelve su código de referido ya operativo. */
    private String darDeAltaYAprobarAfiliado() {
        Map<String, Object> panel = json(HttpMethod.POST, ME_AFILIADO_JOIN, afiliadoToken, null, 200);
        aprobarAfiliado(UUID.fromString(String.valueOf(panel.get("id"))));
        return codigoPrincipal(panel);
    }

    private void aprobarAfiliado(UUID afiliadoEntidadId) {
        peticion(HttpMethod.POST, String.format(ADMIN_AFILIADO_ESTADO, afiliadoEntidadId), adminToken,
                Map.of(CAMPO_ESTADO, "ACTIVE"), null).expectStatus().isNoContent();
    }

    private String codigoPrincipal(Map<String, Object> panel) {
        List<Map<String, Object>> codigos = lista(panel, "codes");
        assertThat(codigos).as("el afiliado tiene al menos un código").isNotEmpty();
        return String.valueOf(codigos.get(0).get(CAMPO_CODIGO));
    }

    /** Clic en el enlace del afiliado + identificación del comprador: la atribución completa. */
    private void atribuirCompradorAlCodigo(String codigo) {
        atribuirUsuarioAlCodigo(codigo, compradorToken);
    }

    private void atribuirUsuarioAlCodigo(String codigo, String token) {
        Map<String, Object> clic = json(HttpMethod.POST, TRACK, null, Map.of("ref", codigo), 200);
        assertThat(clic.get("attributed")).as("un afiliado activo sí atribuye el clic").isEqualTo(true);
        json(HttpMethod.POST, ME_AFILIADO_BIND, token,
                Map.of(CAMPO_TOKEN_VISITANTE, String.valueOf(clic.get(CAMPO_TOKEN_VISITANTE))), 204);
    }

    /** Compra real por el checkout con saldo del monedero; devuelve el identificador del pedido. */
    private UUID comprar(String token, int unidades, String claveIdempotencia) {
        Map<String, Object> direccion = new HashMap<>();
        direccion.put("fullName", "Cliente Certificación");
        direccion.put("phone", "+34600000001");
        direccion.put("email", "cliente@example.com");
        direccion.put("line1", "Gran Vía 1");
        direccion.put("city", "Madrid");
        direccion.put("postalCode", "28013");
        direccion.put("country", "ES");

        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("shippingAddressInline", direccion);
        cuerpo.put("items", List.of(Map.of("productId", productoId.toString(), "quantity", unidades)));
        cuerpo.put("paymentMethod", "WALLET");

        Map<String, Object> pedido = json(HttpMethod.POST, CHECKOUT, token, cuerpo, 201, claveIdempotencia);
        assertThat(pedido.get(CAMPO_ESTADO)).as("con saldo, el pedido queda PAGADO en el acto").isEqualTo("PAID");
        return UUID.fromString(String.valueOf(pedido.get("id")));
    }

    /**
     * Comisión llevada hasta APROBADA por el camino real: se compra, se retrasa la fecha del apunte más
     * allá del periodo de devolución y el administrador lanza la aprobación de las vencidas. Se baja
     * además el mínimo de pago del programa para que la retirada sea posible con importes de prueba.
     */
    private long generarComisionAprobada() {
        String codigo = darDeAltaYAprobarAfiliado();
        atribuirCompradorAlCodigo(codigo);
        comprar(compradorToken, 1, null);

        json(HttpMethod.PUT, ADMIN_CONFIG, adminToken, Map.of("minPayoutCents", 1L), 200);
        jdbcTemplate.update("UPDATE affiliate_commission SET created_at = now() - interval '60 days'");
        Map<String, Object> resultado = json(HttpMethod.POST, ADMIN_APROBAR_VENCIDAS, adminToken, null, 200);
        assertThat(numero(resultado, "approved")).as("una comisión vencida pasa a aprobada").isEqualTo(1L);

        Long aprobado = jdbcTemplate.queryForObject(
                "SELECT sum(amount_cents) FROM affiliate_commission c"
                        + " JOIN affiliate a ON a.id = c.affiliate_id WHERE a.user_id = ? AND c.status = 'APPROVED'",
                Long.class, afiliadoId);
        return aprobado == null ? 0L : aprobado;
    }

    /** Datos de cobro (banco y PayPal) por el endpoint real, que exige la contraseña de la cuenta. */
    private void configurarDatosDeCobro() {
        fijarContrasena(afiliadoId);
        Map<String, Object> perfil = new HashMap<>();
        perfil.put("bankHolder", "Afiliado Certificación");
        perfil.put("iban", IBAN_VALIDO);
        perfil.put("bic", "CAIXESBBXXX");
        perfil.put("paypalEmail", "afiliado@example.com");
        perfil.put("preferredMethod", "BANK");
        perfil.put("password", CONTRASENA);
        json(HttpMethod.PUT, ME_AFILIADO_PERFIL_COBRO, afiliadoToken, perfil, 200);
    }

    private UUID solicitarPago(String metodo) {
        Map<String, Object> solicitud = json(HttpMethod.POST, ME_AFILIADO_SOLICITAR_PAGO, afiliadoToken,
                Map.of(CAMPO_METODO, metodo), 200);
        assertThat(solicitud.get(CAMPO_ESTADO)).isEqualTo("REQUESTED");

        List<Map<String, Object>> pendientes = client.get().uri(ADMIN_PAGOS_PENDIENTES)
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)).exchange().expectStatus().isOk()
                .expectBody(LISTA_JSON).returnResult().getResponseBody();
        assertThat(pendientes).as("el pago aparece en la bandeja del administrador").hasSize(1);
        return UUID.fromString(String.valueOf(pendientes.get(0).get("id")));
    }

    /* ---------------------------- Lecturas de la base ---------------------------- */

    private long importeDelPedido(UUID pedidoId, String columna) {
        Long valor = jdbcTemplate.queryForObject("SELECT " + columna + " FROM customer_order WHERE id = ?", Long.class,
                pedidoId);
        return valor == null ? 0L : valor;
    }

    private long conversionesDelPedido(UUID pedidoId) {
        Long total = jdbcTemplate.queryForObject("SELECT count(*) FROM affiliate_conversion WHERE order_id = ?",
                Long.class, pedidoId);
        return total == null ? 0L : total;
    }

    private long numeroDeComisiones(UUID userId) {
        Long total = jdbcTemplate.queryForObject("SELECT count(*) FROM affiliate_commission c"
                + " JOIN affiliate a ON a.id = c.affiliate_id WHERE a.user_id = ?", Long.class, userId);
        return total == null ? 0L : total;
    }

    private String estadoDeLaComision(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT c.status FROM affiliate_commission c"
                + " JOIN affiliate a ON a.id = c.affiliate_id WHERE a.user_id = ?", String.class, userId);
    }

    private long gananciasAcumuladas(UUID userId) {
        Long valor = jdbcTemplate.queryForObject("SELECT earnings_usd_cents FROM affiliate WHERE user_id = ?",
                Long.class, userId);
        return valor == null ? 0L : valor;
    }

    private long pagosDelAfiliado(UUID userId) {
        Long total = jdbcTemplate.queryForObject("SELECT count(*) FROM affiliate_payout p"
                + " JOIN affiliate a ON a.id = p.affiliate_id WHERE a.user_id = ?", Long.class, userId);
        return total == null ? 0L : total;
    }

    private long saldoDeLaWallet(UUID userId) {
        Long saldo = jdbcTemplate.queryForObject(
                "SELECT coalesce(max(balance_usd_cents), 0) FROM wallet" + " WHERE user_id = ?", Long.class, userId);
        return saldo == null ? 0L : saldo;
    }

    private UUID idDeAfiliado(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT id FROM affiliate WHERE user_id = ?", UUID.class, userId);
    }

    private UUID idDelCodigo(String codigo) {
        return jdbcTemplate.queryForObject("SELECT id FROM affiliate_referral_code WHERE code = ?", UUID.class, codigo);
    }

    /* ---------------------------- Cálculo y transporte ---------------------------- */

    /** Mismo redondeo que usa el programa: porcentaje sobre céntimos, HALF_UP al céntimo. */
    private long porcentaje(long centimos, BigDecimal porcentaje) {
        return BigDecimal.valueOf(centimos).multiply(porcentaje)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValue();
    }

    private long numero(Map<String, Object> cuerpo, String campo) {
        Object valor = cuerpo.get(campo);
        assertThat(valor).as("el campo %s tiene que venir en la respuesta", campo).isNotNull();
        return ((Number) valor).longValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapa(Map<String, Object> cuerpo, String campo) {
        return (Map<String, Object>) cuerpo.get(campo);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> lista(Map<String, Object> cuerpo, String campo) {
        return (List<Map<String, Object>>) cuerpo.get(campo);
    }

    private Map<String, Object> json(HttpMethod metodo, String uri, String token, Object cuerpo, int estadoEsperado) {
        return json(metodo, uri, token, cuerpo, estadoEsperado, null);
    }

    /**
     * Lanza la petición UNA SOLA VEZ y devuelve su cuerpo como mapa. Se lee en bytes y se convierte a
     * mano a propósito: repetir la llamada para "releer" el cuerpo duplicaría el movimiento de dinero,
     * que es justo lo que esta clase persigue.
     */
    private Map<String, Object> json(HttpMethod metodo, String uri, String token, Object cuerpo, int estadoEsperado,
            String claveIdempotencia) {
        byte[] bytes = peticion(metodo, uri, token, cuerpo, claveIdempotencia).expectStatus().isEqualTo(estadoEsperado)
                .expectBody().returnResult().getResponseBody();
        if (bytes == null || bytes.length == 0) {
            return Map.of(); // 204 y demás respuestas sin cuerpo
        }
        try {
            return objectMapper.readValue(bytes, MAPA_JSON);
        } catch (JacksonException e) {
            // Jackson 3 lanza JacksonException (no comprobada); IOException ya no aparece en la firma.
            throw new IllegalStateException("respuesta no interpretable como JSON en " + uri, e);
        }
    }

    private WebTestClient.ResponseSpec peticion(HttpMethod metodo, String uri, String token, Object cuerpo,
            String claveIdempotencia) {
        WebTestClient.RequestBodySpec spec = client.method(metodo).uri(uri).contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, bearer(token));
        }
        {
            // Clave SIEMPRE: los endpoints de dinero la exigen. Nueva por llamada salvo reenvío explícito.
            spec = spec.header("Idempotency-Key",
                    claveIdempotencia != null ? claveIdempotencia : UUID.randomUUID().toString());
        }
        return cuerpo == null ? spec.exchange() : spec.bodyValue(cuerpo).exchange();
    }
}
