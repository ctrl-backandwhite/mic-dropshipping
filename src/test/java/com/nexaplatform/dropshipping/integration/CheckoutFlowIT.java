package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.config.BaseIntegration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recorrido COMPLETO del checkout por HTTP: carrito → cotización → pedido pagado con saldo.
 *
 * <p>Por qué existe: la suite tenía 88 pruebas de integración y ninguna recorría un checkout entero. Los
 * tres fallos que costaron dinero —el doble gasto del 14-ago, el arancel cobrado de más y el cupón que se
 * sumaba a la rebaja— pasaron por delante de una suite verde porque nadie comprobaba el IMPORTE, solo el
 * código de estado. Aquí la regla es innegociable: <b>cada respuesta con dinero se contrasta contra un
 * número calculado a mano</b>, en céntimos. Un 200 con la cifra equivocada es un fallo.
 *
 * <p><b>Cómo se consigue que las cuentas sean predecibles.</b> {@code BaseIntegration} vacía TODAS las
 * tablas antes de cada prueba, incluidas las que traen los seeds (tarifas de envío, IVA por país, reglas
 * de margen, aduanas). Eso deja el cálculo desnudo y cada prueba siembra solo lo que necesita:
 * <ul>
 *   <li>producto en USD ({@code currency='USD'}) → la conversión de divisa es la identidad;</li>
 *   <li>sin {@code price_rule} → sin margen: el precio de venta ES el coste, y el número esperado se
 *       escribe a mano en la prueba en vez de depender del 150% que haya configurado producción;</li>
 *   <li>zona de envío con {@code per_kg_cents = 0} → el porte es una constante y el peso no lo mueve;</li>
 *   <li>sin {@code country_tax_rate} ni {@code country_customs_rule} → impuesto y despacho a cero salvo
 *       en las pruebas que los siembran a propósito.</li>
 * </ul>
 *
 * <p>El envío total se cobra igual en la vista previa ({@code POST /api/shipping/quote}) y en el pedido
 * ({@code POST /api/me/orders/checkout}) porque los dos pasan por {@code CheckoutTotalsService}; varias
 * pruebas comparan las dos respuestas AL CÉNTIMO precisamente para que no vuelvan a separarse.
 */
class CheckoutFlowIT extends BaseIntegration {

    private static final String CHECKOUT = "/api/me/orders/checkout";
    private static final String COTIZAR = "/api/shipping/quote";
    private static final String DIRECCIONES = "/api/me/addresses";
    private static final String MIS_PEDIDOS = "/api/me/orders";
    /** Cesta sincronizada del usuario: la que tiene que quedar vacía al pagar. */
    private static final String CESTA = "/api/me/cart";
    private static final String CABECERA_IDEMPOTENCIA = "Idempotency-Key";

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LINEAS_DE_CESTA =
            new ParameterizedTypeReference<>() {
            };

    /** País con cobertura sembrada en cada prueba. */
    private static final String PAIS = "ES";
    /** País deliberadamente SIN fila en {@code cainiao_shipping_zone}: destino no soportado. */
    private static final String PAIS_SIN_COBERTURA = "FR";

    /** Tarifa plana del transportista: base fija y 0 por kilo, para que el porte no dependa del peso. */
    private static final int ENVIO_CENTS = 500;
    /** Precio de proveedor del producto base, en USD. Sin margen → 10,00 $ de venta = 1000 céntimos. */
    private static final String PRECIO_BASE = "10.0000";
    private static final int UNIDAD_CENTS = 1000;
    /**
     * Saldo cómodo para las pruebas que no van sobre el saldo. Tiene que cubrir también el caso del tope
     * de cantidad (100.000 unidades a 10,00 $ = 1.000.000 $), o ese borde fallaría por falta de fondos y
     * no por lo que se quiere medir.
     */
    private static final long SALDO_HOLGADO = 500_000_000L;

    /** Tope de unidades por línea que aplican el DTO ({@code @Max}) y el dominio a la vez. */
    private static final int CANTIDAD_MAXIMA = 100_000;
    /** Tope de líneas por pedido ({@code @Size(max = 100)}). */
    private static final int LINEAS_MAXIMAS = 100;

    /**
     * USE_BIG_DECIMAL_FOR_FLOATS: sin él Jackson lee los importes como {@code double} y una comparación
     * "al céntimo" pasaría a depender del binario en coma flotante, que es justo lo que no queremos medir.
     */
    // Jackson 3: el ObjectMapper es INMUTABLE, la configuración se fija al construirlo.
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    /**
     * Las reglas de margen viven en una caché de 5 minutos que se calienta al arrancar el contexto, ANTES
     * de que el TRUNCATE se lleve las reglas sembradas. Sin invalidarla, la primera prueba tarificaría con
     * el margen de los seeds y las siguientes sin él: el mismo test daría números distintos según el orden.
     */
    @Autowired
    private MarginService marginService;

    private UUID userId;
    private String token;
    private UUID productId;
    private UUID direccionId;

    @BeforeEach
    void prepararEscenario() {
        marginService.invalidateCache();
        userId = UUID.randomUUID();
        token = jwt.userToken(userId, "checkout@nx036.local", "USER");
        insertarUsuario(userId, "checkout@nx036.local");
        acreditarWallet(userId, SALDO_HOLGADO);
        insertarZona(PAIS, ENVIO_CENTS);
        productId = insertarProducto(PRECIO_BASE, "0");
        direccionId = crearDireccion(PAIS);
    }

    /* ==================================================================================
     *  A · El recorrido completo y el importe exacto
     * ================================================================================== */

    @Test
    @DisplayName("carrito → cotización → pedido con WALLET: cobra el importe exacto y deja el saldo cuadrado")
    void recorridoCompletoDelCheckoutCobraElImporteExacto() {
        // 3 × 10,00 $ + 5,00 $ de envío, sin IVA ni aduana configurados.
        int subtotalEsperado = 3 * UNIDAD_CENTS;
        int totalEsperado = subtotalEsperado + ENVIO_CENTS;
        long saldoAntes = saldoEnBd();

        JsonNode cotizacion = cuerpo(cotizar(3, null).expectStatus().isOk());
        assertThat(cotizacion.get("supported").asBoolean()).isTrue();
        assertThat(cotizacion.get("subtotalUsdCents").asInt()).isEqualTo(subtotalEsperado);
        assertThat(cotizacion.get("amountUsdCents").asInt()).isEqualTo(ENVIO_CENTS);

        JsonNode pedido = cuerpo(checkout(pedidoDe(3), null).expectStatus().isCreated());

        assertThat(pedido.get("status").asText()).isEqualTo("PAID");
        assertThat(centimos(pedido, "subtotal")).isEqualTo(subtotalEsperado);
        assertThat(centimos(pedido, "shipping")).isEqualTo(ENVIO_CENTS);
        assertThat(centimos(pedido, "tax")).isZero();
        assertThat(centimos(pedido, "discount")).isZero();
        assertThat(centimos(pedido, "total"))
                .as("3 unidades a 10,00 $ más 5,00 $ de envío son 35,00 $, ni un céntimo más")
                .isEqualTo(totalEsperado);

        assertThat(pedidosEnBd()).isEqualTo(1);
        assertThat(totalCobradoEnBd()).isEqualTo(totalEsperado);
        assertThat(saldoEnBd())
                .as("del monedero tiene que salir EXACTAMENTE el total del pedido")
                .isEqualTo(saldoAntes - totalEsperado);
        assertThat(cargosAlMonedero()).isEqualTo(totalEsperado);
    }

    @Test
    @DisplayName("la vista previa del checkout y el pedido realmente cobrado coinciden al céntimo")
    void vistaPreviaYPedidoCoincidenAlCentimo() {
        JsonNode previa = cuerpo(cotizar(4, null).expectStatus().isOk());
        JsonNode pedido = cuerpo(checkout(pedidoDe(4), null).expectStatus().isCreated());

        assertThat(previa.get("subtotalUsdCents").asInt()).isEqualTo(centimos(pedido, "subtotal"));
        assertThat(previa.get("amountUsdCents").asInt()).isEqualTo(centimos(pedido, "shipping"));
        assertThat(previa.get("discountCents").asInt()).isEqualTo(centimos(pedido, "discount"));
        assertThat(centimosDeTexto(previa.get("taxFormatted").asText())).isEqualTo(centimos(pedido, "tax"));
        assertThat(centimosDeTexto(previa.get("totalFormatted").asText()))
                .as("lo que se le enseña al cliente antes de pagar es lo que se le cobra")
                .isEqualTo(centimos(pedido, "total"));
    }

    /* ==================================================================================
     *  B · El precio lo pone el SERVIDOR
     * ================================================================================== */

    @Test
    @DisplayName("un precio unitario inyectado en el cuerpo de la línea se ignora: manda el catálogo")
    void elPrecioUnitarioInyectadoEnElCuerpoSeIgnora() {
        String cuerpoManipulado = """
                {"shippingAddressId":"%s","paymentMethod":"WALLET","items":[
                  {"productId":"%s","quantity":3,"unitPriceCents":1,"price":0.01,"lineTotalCents":3,
                   "unitPriceUsd":0.01,"costCents":0}]}
                """.formatted(direccionId, productId);

        JsonNode pedido = cuerpo(checkout(cuerpoManipulado, null).expectStatus().isCreated());

        assertThat(centimos(pedido, "subtotal")).isEqualTo(3 * UNIDAD_CENTS);
        assertThat(centimos(pedido, "total"))
                .as("con el precio del atacante el pedido costaría 0,03 $; el servidor cobra 35,00 $")
                .isEqualTo(3 * UNIDAD_CENTS + ENVIO_CENTS);
        assertThat(saldoEnBd()).isEqualTo(SALDO_HOLGADO - (3 * UNIDAD_CENTS + ENVIO_CENTS));
    }

    @Test
    @DisplayName("los totales inyectados en la raíz del cuerpo (total, descuento, envío) se ignoran")
    void losTotalesInyectadosEnLaRaizSeIgnoran() {
        String cuerpoManipulado = """
                {"shippingAddressId":"%s","paymentMethod":"WALLET","total":0.01,"subtotal":0,
                 "totalCents":1,"subtotalCents":0,"discountCents":999999,"shippingCents":0,"taxCents":0,
                 "currency":"USD","status":"PAID",
                 "items":[{"productId":"%s","quantity":2}]}
                """.formatted(direccionId, productId);

        JsonNode pedido = cuerpo(checkout(cuerpoManipulado, null).expectStatus().isCreated());

        assertThat(centimos(pedido, "discount")).as("el descuento no se pide, se gana").isZero();
        assertThat(centimos(pedido, "shipping")).isEqualTo(ENVIO_CENTS);
        assertThat(centimos(pedido, "total")).isEqualTo(2 * UNIDAD_CENTS + ENVIO_CENTS);
    }

    /* ==================================================================================
     *  C · Subtotal = Σ(unitario × cantidad), sin desviación de redondeo
     * ================================================================================== */

    @Test
    @DisplayName("el subtotal es la suma de unitario × cantidad, sin desviación de redondeo")
    void elSubtotalEsLaSumaDeUnitarioPorCantidad() {
        // 10,0050 $ de coste redondea al céntimo MÁS CERCANO → 10,01 $ la unidad. Siete unidades son
        // 70,07 $. Quien redondee el total en vez de la unidad se queda en 70,04 $ y cobra de menos.
        productId = insertarProducto("10.0050", "0");

        JsonNode pedido = cuerpo(checkout(pedidoDe(7), null).expectStatus().isCreated());

        assertThat(centimos(pedido, "subtotal")).isEqualTo(7 * 1001);
        assertThat(centimos(pedido, "subtotal")).as("no es round(10,0050 × 7) = 7004").isNotEqualTo(7004);
        assertThat(centimos(pedido, "total")).isEqualTo(7 * 1001 + ENVIO_CENTS);
    }

    @Test
    @DisplayName("el medio céntimo del precio unitario redondea hacia arriba, igual que el catálogo")
    void elMedioCentimoDelPrecioUnitarioRedondeaHaciaArriba() {
        productId = insertarProducto("10.0050", "0");

        JsonNode pedido = cuerpo(checkout(pedidoDe(1), null).expectStatus().isCreated());

        assertThat(centimos(pedido, "subtotal")).as("10,005 → 10,01, no 10,00").isEqualTo(1001);
    }

    @Test
    @DisplayName("el total escala linealmente con la cantidad: la diferencia es exactamente el precio unitario")
    void elTotalEscalaLinealmenteConLaCantidad() {
        JsonNode unaUnidad = cuerpo(checkout(pedidoDe(1), null).expectStatus().isCreated());
        JsonNode tresUnidades = cuerpo(checkout(pedidoDe(3), null).expectStatus().isCreated());

        assertThat(centimos(unaUnidad, "total")).isEqualTo(UNIDAD_CENTS + ENVIO_CENTS);
        assertThat(centimos(tresUnidades, "total")).isEqualTo(3 * UNIDAD_CENTS + ENVIO_CENTS);
        assertThat(centimos(tresUnidades, "total") - centimos(unaUnidad, "total"))
                .as("el envío es plano, así que la diferencia son dos unidades justas")
                .isEqualTo(2 * UNIDAD_CENTS);
    }

    @Test
    @DisplayName("dos líneas del mismo producto suman igual que una sola línea con la cantidad total")
    void dosLineasDelMismoProductoSumanIgualQueUna() {
        String dosLineas = """
                {"shippingAddressId":"%s","paymentMethod":"WALLET","items":[
                  {"productId":"%s","quantity":2},{"productId":"%s","quantity":3}]}
                """.formatted(direccionId, productId, productId);

        JsonNode pedido = cuerpo(checkout(dosLineas, null).expectStatus().isCreated());

        assertThat(centimos(pedido, "subtotal")).isEqualTo(5 * UNIDAD_CENTS);
        assertThat(centimos(pedido, "total")).isEqualTo(5 * UNIDAD_CENTS + ENVIO_CENTS);
    }

    /* ==================================================================================
     *  D · Impuesto del país de destino
     * ================================================================================== */

    @Test
    @DisplayName("el impuesto del país se calcula sobre (subtotal − descuento) + envío, al céntimo")
    void elImpuestoSeCalculaSobreSubtotalMasEnvio() {
        insertarIva(PAIS, 2100);
        // Base imponible = 3 × 1000 + 500 = 3500 → 21% = 735 exactos.
        int subtotal = 3 * UNIDAD_CENTS;
        int impuestoEsperado = 735;

        JsonNode pedido = cuerpo(checkout(pedidoDe(3), null).expectStatus().isCreated());

        assertThat(centimos(pedido, "tax")).isEqualTo(impuestoEsperado);
        assertThat(centimos(pedido, "total")).isEqualTo(subtotal + ENVIO_CENTS + impuestoEsperado);
        assertThat(saldoEnBd()).isEqualTo(SALDO_HOLGADO - (subtotal + ENVIO_CENTS + impuestoEsperado));
    }

    @Test
    @DisplayName("el impuesto que cae en medio céntimo redondea hacia arriba y no se pierde por el camino")
    void elImpuestoRedondeaElMedioCentimoHaciaArriba() {
        // 15,0050 $ → 15,01 la unidad; 2 unidades = 3002; base imponible 3002 + 500 = 3502.
        // 3502 × 25% = 875,5 → 876 (HALF_UP). Truncar daría 875 y el pedido saldría un céntimo barato.
        productId = insertarProducto("15.0050", "0");
        insertarIva(PAIS, 2500);

        JsonNode pedido = cuerpo(checkout(pedidoDe(2), null).expectStatus().isCreated());

        assertThat(centimos(pedido, "subtotal")).isEqualTo(3002);
        assertThat(centimos(pedido, "tax")).isEqualTo(876);
        assertThat(centimos(pedido, "tax")).as("no se trunca a 875").isNotEqualTo(875);
        assertThat(centimos(pedido, "total")).isEqualTo(3002 + ENVIO_CENTS + 876);
    }

    @Test
    @DisplayName("con impuesto configurado, la vista previa sigue coincidiendo al céntimo con el cobro")
    void conImpuestoLaVistaPreviaSigueCoincidiendo() {
        productId = insertarProducto("15.0050", "0");
        insertarIva(PAIS, 2500);

        JsonNode previa = cuerpo(cotizar(2, null).expectStatus().isOk());
        JsonNode pedido = cuerpo(checkout(pedidoDe(2), null).expectStatus().isCreated());

        assertThat(previa.get("taxRateBps").asInt()).isEqualTo(2500);
        assertThat(centimosDeTexto(previa.get("taxFormatted").asText())).isEqualTo(centimos(pedido, "tax"));
        assertThat(centimosDeTexto(previa.get("totalFormatted").asText())).isEqualTo(centimos(pedido, "total"));
    }

    @Test
    @DisplayName("una tasa de impuesto a cero no añade nada al total")
    void unaTasaAceroNoAnadeNada() {
        insertarIva(PAIS, 0);

        JsonNode pedido = cuerpo(checkout(pedidoDe(1), null).expectStatus().isCreated());

        assertThat(centimos(pedido, "tax")).isZero();
        assertThat(centimos(pedido, "total")).isEqualTo(UNIDAD_CENTS + ENVIO_CENTS);
    }

    /* ==================================================================================
     *  E · Cupones y promociones: gana el MAYOR, nunca la suma
     * ================================================================================== */

    @Test
    @DisplayName("un cupón inexistente no descuenta, no rompe el pedido y se explica en la vista previa")
    void cuponInexistenteNoDescuentaNiRompe() {
        JsonNode previa = cuerpo(cotizar(2, "NO-EXISTE-9999").expectStatus().isOk());
        assertThat(previa.get("couponError").asText()).isEqualTo("Ese código no existe");
        assertThat(previa.get("discountCents").asInt()).isZero();

        JsonNode pedido = cuerpo(checkout(pedidoDe(2, "NO-EXISTE-9999"), null).expectStatus().isCreated());

        assertThat(centimos(pedido, "discount")).isZero();
        assertThat(centimos(pedido, "total")).isEqualTo(2 * UNIDAD_CENTS + ENVIO_CENTS);
    }

    @Test
    @DisplayName("un cupón caducado no descuenta y el pedido se cobra íntegro")
    void cuponCaducadoNoDescuenta() {
        insertarCupon("CADUCADO", "20", null, Instant.now().minus(30, ChronoUnit.DAYS),
                Instant.now().minus(1, ChronoUnit.DAYS), true, null, 0, null);

        JsonNode previa = cuerpo(cotizar(2, "CADUCADO").expectStatus().isOk());
        assertThat(previa.get("couponError").asText()).isEqualTo("Ese cupón ha caducado");

        JsonNode pedido = cuerpo(checkout(pedidoDe(2, "CADUCADO"), null).expectStatus().isCreated());

        assertThat(centimos(pedido, "discount")).isZero();
        assertThat(centimos(pedido, "total")).isEqualTo(2 * UNIDAD_CENTS + ENVIO_CENTS);
    }

    @Test
    @DisplayName("un cupón que todavía no ha empezado no descuenta")
    void cuponQueNoHaEmpezadoNoDescuenta() {
        insertarCupon("FUTURO", "20", null, Instant.now().plus(1, ChronoUnit.DAYS),
                Instant.now().plus(30, ChronoUnit.DAYS), true, null, 0, null);

        assertThat(cuerpo(cotizar(2, "FUTURO").expectStatus().isOk()).get("couponError").asText())
                .isEqualTo("Ese cupón todavía no ha empezado");
        assertThat(centimos(cuerpo(checkout(pedidoDe(2, "FUTURO"), null).expectStatus().isCreated()), "total"))
                .isEqualTo(2 * UNIDAD_CENTS + ENVIO_CENTS);
    }

    @Test
    @DisplayName("un cupón desactivado no descuenta aunque esté dentro de su vigencia")
    void cuponDesactivadoNoDescuenta() {
        insertarCupon("APAGADO", "20", null, null, null, false, null, 0, null);

        assertThat(cuerpo(cotizar(2, "APAGADO").expectStatus().isOk()).get("couponError").asText())
                .isEqualTo("Ese cupón ya no está disponible");
        assertThat(centimos(cuerpo(checkout(pedidoDe(2, "APAGADO"), null).expectStatus().isCreated()), "total"))
                .isEqualTo(2 * UNIDAD_CENTS + ENVIO_CENTS);
    }

    @Test
    @DisplayName("un cupón agotado (usos consumidos) no descuenta")
    void cuponAgotadoNoDescuenta() {
        insertarCupon("AGOTADO", "20", null, null, null, true, 3, 3, null);

        assertThat(cuerpo(cotizar(2, "AGOTADO").expectStatus().isOk()).get("couponError").asText())
                .isEqualTo("Ese cupón se ha agotado");
        assertThat(centimos(cuerpo(checkout(pedidoDe(2, "AGOTADO"), null).expectStatus().isCreated()), "total"))
                .isEqualTo(2 * UNIDAD_CENTS + ENVIO_CENTS);
    }

    @Test
    @DisplayName("con un uso todavía libre el cupón sí descuenta: el borde de «agotado» es el uso número N")
    void cuponConUnUsoLibreSiDescuenta() {
        insertarCupon("CASI", "20", null, null, null, true, 3, 2, null);

        JsonNode pedido = cuerpo(checkout(pedidoDe(2, "CASI"), null).expectStatus().isCreated());

        // 20% de 2000 = 400.
        assertThat(centimos(pedido, "discount")).isEqualTo(400);
        assertThat(centimos(pedido, "total")).isEqualTo(2 * UNIDAD_CENTS - 400 + ENVIO_CENTS);
    }

    @Test
    @DisplayName("el pedido mínimo del cupón es un «menor que»: con el importe EXACTO el cupón vale")
    void elPedidoMinimoDelCuponSeCumpleEnElValorExacto() {
        insertarCupon("MINIMO", "10", null, null, null, true, null, 0, 2000);

        // Subtotal exactamente 2000 = el mínimo → el cupón entra (la comprobación es subtotal < mínimo).
        JsonNode justo = cuerpo(checkout(pedidoDe(2, "MINIMO"), null).expectStatus().isCreated());
        assertThat(centimos(justo, "discount")).isEqualTo(200);
        assertThat(centimos(justo, "total")).isEqualTo(2000 - 200 + ENVIO_CENTS);
    }

    @Test
    @DisplayName("por debajo del pedido mínimo el cupón no descuenta y el total queda intacto")
    void pordebajoDelPedidoMinimoElCuponNoDescuenta() {
        insertarCupon("MINIMO", "10", null, null, null, true, null, 0, 2000);

        assertThat(cuerpo(cotizar(1, "MINIMO").expectStatus().isOk()).get("couponError").asText())
                .contains("pedido mínimo");
        JsonNode pedido = cuerpo(checkout(pedidoDe(1, "MINIMO"), null).expectStatus().isCreated());
        assertThat(centimos(pedido, "discount")).isZero();
        assertThat(centimos(pedido, "total")).isEqualTo(UNIDAD_CENTS + ENVIO_CENTS);
    }

    @Test
    @DisplayName("promoción automática y cupón a la vez: gana EL MAYOR y nunca se suman")
    void promocionYCuponALaVezGanaElMayorNuncaLaSuma() {
        // Producto de 10,00 $ de base más 5,00 $ de porte propio → 15,00 $ de tarifa; el suelo de las
        // rebajas es la base (10,00 $), así que una rebaja del 10% (13,50 $) cabe de sobra.
        productId = insertarProducto(PRECIO_BASE, "5.0000");
        insertarRebajaAutomatica("Rebaja de temporada", "10");
        insertarCupon("MEJOR30", "30", null, null, null, true, null, 0, null);

        JsonNode pedido = cuerpo(checkout(pedidoDe(1, "MEJOR30"), null).expectStatus().isCreated());

        // Tarifa 1500, rebaja automática ya aplicada en la línea (subtotal 1350, o sea 150 de rebaja).
        // El cupón vale 30% de 1500 = 450: gana, y solo se cobra la DIFERENCIA (450 − 150 = 300).
        assertThat(centimos(pedido, "subtotal")).isEqualTo(1350);
        assertThat(centimos(pedido, "discount")).isEqualTo(300);
        assertThat(centimos(pedido, "total"))
                .as("1350 − 300 + 500 de envío = 1550")
                .isEqualTo(1550);
        assertThat(centimos(pedido, "total"))
                .as("sumar los dos descuentos daría 1400: eso es exactamente lo que la regla prohíbe")
                .isNotEqualTo(1400);
        assertThat(saldoEnBd()).isEqualTo(SALDO_HOLGADO - 1550);
    }

    @Test
    @DisplayName("un cupón que EMPATA con la rebaja automática no se aplica (el borde exacto del «mayor que»)")
    void elCuponQueEmpataConLaRebajaNoSeAplica() {
        productId = insertarProducto(PRECIO_BASE, "5.0000");
        insertarRebajaAutomatica("Rebaja de temporada", "10");
        insertarCupon("EMPATE10", "10", null, null, null, true, null, 0, null);

        JsonNode previa = cuerpo(cotizar(1, "EMPATE10").expectStatus().isOk());
        assertThat(previa.get("couponError").asText()).isEqualTo("Ya tienes un descuento mejor aplicado");

        JsonNode pedido = cuerpo(checkout(pedidoDe(1, "EMPATE10"), null).expectStatus().isCreated());
        assertThat(centimos(pedido, "discount")).isZero();
        assertThat(centimos(pedido, "total")).isEqualTo(1350 + ENVIO_CENTS);
    }

    @Test
    @DisplayName("un punto por encima del empate el cupón sí gana, y solo por la diferencia")
    void unPuntoPorEncimaDelEmpateElCuponGana() {
        productId = insertarProducto(PRECIO_BASE, "5.0000");
        insertarRebajaAutomatica("Rebaja de temporada", "10");
        insertarCupon("APENAS11", "11", null, null, null, true, null, 0, null);

        JsonNode pedido = cuerpo(checkout(pedidoDe(1, "APENAS11"), null).expectStatus().isCreated());

        // 11% de 1500 = 165 (se trunca), menos los 150 ya rebajados = 15 de diferencia.
        assertThat(centimos(pedido, "discount")).isEqualTo(15);
        assertThat(centimos(pedido, "total")).isEqualTo(1350 - 15 + ENVIO_CENTS);
    }

    @Test
    @DisplayName("un cupón peor que la rebaja que ya tenía el producto no se aplica ni empeora el precio")
    void elCuponPeorQueLaRebajaNoSeAplica() {
        productId = insertarProducto(PRECIO_BASE, "5.0000");
        insertarRebajaAutomatica("Rebaja de temporada", "10");
        insertarCupon("PEOR5", "5", null, null, null, true, null, 0, null);

        JsonNode pedido = cuerpo(checkout(pedidoDe(1, "PEOR5"), null).expectStatus().isCreated());

        assertThat(centimos(pedido, "discount")).isZero();
        assertThat(centimos(pedido, "total"))
                .as("el cliente conserva su rebaja del 10%: canjear un código nunca puede salirle caro")
                .isEqualTo(1350 + ENVIO_CENTS);
    }

    @Test
    @DisplayName("el descuento no baja del precio base: una rebaja del 50% se recorta al suelo")
    void elDescuentoNoBajaDelPrecioBase() {
        // Tarifa 15,00 $ = base 10,00 $ + porte 5,00 $. El suelo de las rebajas es la BASE.
        productId = insertarProducto(PRECIO_BASE, "5.0000");
        insertarRebajaAutomatica("Liquidación agresiva", "50");

        JsonNode pedido = cuerpo(checkout(pedidoDe(1), null).expectStatus().isCreated());

        assertThat(centimos(pedido, "subtotal"))
                .as("un −50% sobre 1500 daría 750; el suelo lo deja en la base, 1000")
                .isEqualTo(1000);
        assertThat(centimos(pedido, "subtotal")).isNotEqualTo(750);
        assertThat(centimos(pedido, "total")).isEqualTo(1000 + ENVIO_CENTS);
    }

    @Test
    @DisplayName("un cupón de importe fijo enorme no deja el subtotal en negativo: se topa al importe alcanzable")
    void elCuponDeImporteFijoNoDejaElSubtotalEnNegativo() {
        insertarCupon("REGALO", null, 999_999, null, null, true, null, 0, null);

        JsonNode pedido = cuerpo(checkout(pedidoDe(2, "REGALO"), null).expectStatus().isCreated());

        assertThat(centimos(pedido, "discount")).isEqualTo(2 * UNIDAD_CENTS);
        assertThat(centimos(pedido, "total"))
                .as("el producto queda a cero, pero el envío se sigue cobrando: nunca un total negativo")
                .isEqualTo(ENVIO_CENTS);
        assertThat(saldoEnBd()).isEqualTo(SALDO_HOLGADO - ENVIO_CENTS);
    }

    @Test
    @DisplayName("un cupón de importe fijo descuenta ese importe exacto, ni más ni menos")
    void elCuponDeImporteFijoDescuentaLoJusto() {
        insertarCupon("MENOS7", null, 700, null, null, true, null, 0, null);

        JsonNode pedido = cuerpo(checkout(pedidoDe(2, "MENOS7"), null).expectStatus().isCreated());

        assertThat(centimos(pedido, "discount")).isEqualTo(700);
        assertThat(centimos(pedido, "total")).isEqualTo(2 * UNIDAD_CENTS - 700 + ENVIO_CENTS);
    }

    @Test
    @DisplayName("un cupón en minúsculas es el mismo cupón: el código no distingue mayúsculas")
    void elCodigoDelCuponNoDistingueMayusculas() {
        insertarCupon("VERANO25", "20", null, null, null, true, null, 0, null);

        JsonNode pedido = cuerpo(checkout(pedidoDe(2, "verano25"), null).expectStatus().isCreated());

        assertThat(centimos(pedido, "discount")).isEqualTo(400);
        assertThat(centimos(pedido, "total")).isEqualTo(2 * UNIDAD_CENTS - 400 + ENVIO_CENTS);
    }

    @Test
    @DisplayName("un código de cupón vacío se trata como «sin cupón», no como cupón inválido")
    void elCuponVacioSeTrataComoSinCupon() {
        String cuerpoConCuponVacio = """
                {"shippingAddressId":"%s","paymentMethod":"WALLET","couponCode":"",
                 "items":[{"productId":"%s","quantity":2}]}
                """.formatted(direccionId, productId);

        JsonNode pedido = cuerpo(checkout(cuerpoConCuponVacio, null).expectStatus().isCreated());

        assertThat(centimos(pedido, "discount")).isZero();
        assertThat(centimos(pedido, "total")).isEqualTo(2 * UNIDAD_CENTS + ENVIO_CENTS);
    }

    @Test
    @DisplayName("el impuesto se calcula DESPUÉS del descuento del cupón, no sobre el precio sin rebajar")
    void elImpuestoSeCalculaSobreElSubtotalYaDescontado() {
        insertarIva(PAIS, 2000);
        insertarCupon("MENOS10", null, 1000, null, null, true, null, 0, null);

        // Subtotal 2000 − 1000 de cupón = 1000; base imponible 1000 + 500 = 1500; 20% = 300.
        JsonNode pedido = cuerpo(checkout(pedidoDe(2, "MENOS10"), null).expectStatus().isCreated());

        assertThat(centimos(pedido, "discount")).isEqualTo(1000);
        assertThat(centimos(pedido, "tax")).isEqualTo(300);
        assertThat(centimos(pedido, "total")).isEqualTo(1000 + ENVIO_CENTS + 300);
    }

    /* ==================================================================================
     *  F · Cantidades: cero, negativa, desbordante y los bordes del tope
     * ================================================================================== */

    @Test
    @DisplayName("cantidad 0 se rechaza con 400 y no crea pedido ni mueve el saldo")
    void cantidadCeroSeRechaza() {
        checkout(pedidoDe(0), null).expectStatus().isBadRequest();

        assertThat(pedidosEnBd()).isZero();
        assertThat(saldoEnBd()).isEqualTo(SALDO_HOLGADO);
    }

    @Test
    @DisplayName("cantidad negativa se rechaza con 400 y no descuenta importe alguno")
    void cantidadNegativaSeRechaza() {
        checkout(pedidoDe(-5), null).expectStatus().isBadRequest();

        assertThat(pedidosEnBd()).isZero();
        assertThat(saldoEnBd()).isEqualTo(SALDO_HOLGADO);
    }

    @Test
    @DisplayName("cantidad desbordante (2147483647) se rechaza con 400 sin romper el importe")
    void cantidadDesbordanteSeRechaza() {
        checkout(pedidoDe(Integer.MAX_VALUE), null).expectStatus().isBadRequest();

        assertThat(pedidosEnBd()).isZero();
        assertThat(saldoEnBd()).isEqualTo(SALDO_HOLGADO);
    }

    @Test
    @DisplayName("una cantidad que ni siquiera cabe en un entero se rechaza con 400")
    void cantidadFueraDelRangoDeEnteroSeRechaza() {
        String cuerpoEnorme = """
                {"shippingAddressId":"%s","paymentMethod":"WALLET",
                 "items":[{"productId":"%s","quantity":99999999999999}]}
                """.formatted(direccionId, productId);

        checkout(cuerpoEnorme, null).expectStatus().isBadRequest();

        assertThat(pedidosEnBd()).isZero();
        assertThat(saldoEnBd()).isEqualTo(SALDO_HOLGADO);
    }

    @Test
    @DisplayName("la cantidad máxima permitida (100.000) se acepta y multiplica sin desbordar el importe")
    void laCantidadMaximaSeAceptaYMultiplicaBien() {
        JsonNode pedido = cuerpo(checkout(pedidoDe(CANTIDAD_MAXIMA), null).expectStatus().isCreated());

        int subtotalEsperado = CANTIDAD_MAXIMA * UNIDAD_CENTS; // 100.000.000 céntimos = 1.000.000 $
        assertThat(centimos(pedido, "subtotal")).isEqualTo(subtotalEsperado);
        assertThat(centimos(pedido, "total"))
                .as("el producto cabe en un entero: si desbordara, saldría un importe pequeño y se cobraría de menos")
                .isEqualTo(subtotalEsperado + ENVIO_CENTS);
        assertThat(saldoEnBd()).isEqualTo(SALDO_HOLGADO - (long) (subtotalEsperado + ENVIO_CENTS));
    }

    @Test
    @DisplayName("una unidad por encima del tope de línea se rechaza con 400")
    void unaUnidadPorEncimaDelTopeSeRechaza() {
        checkout(pedidoDe(CANTIDAD_MAXIMA + 1), null).expectStatus().isBadRequest();

        assertThat(pedidosEnBd()).isZero();
    }

    @Test
    @DisplayName("una línea sin el campo cantidad se rechaza con 400 (no se asume 1)")
    void lineaSinCantidadSeRechaza() {
        String sinCantidad = """
                {"shippingAddressId":"%s","paymentMethod":"WALLET","items":[{"productId":"%s"}]}
                """.formatted(direccionId, productId);

        checkout(sinCantidad, null).expectStatus().isBadRequest();

        assertThat(pedidosEnBd()).isZero();
    }

    @Test
    @DisplayName("un carrito vacío se rechaza con 400")
    void carritoVacioSeRechaza() {
        String vacio = """
                {"shippingAddressId":"%s","paymentMethod":"WALLET","items":[]}
                """.formatted(direccionId);

        checkout(vacio, null).expectStatus().isBadRequest();

        assertThat(pedidosEnBd()).isZero();
    }

    @Test
    @DisplayName("un carrito sin el campo items se rechaza con 400")
    void carritoSinCampoItemsSeRechaza() {
        String sinItems = """
                {"shippingAddressId":"%s","paymentMethod":"WALLET"}
                """.formatted(direccionId);

        checkout(sinItems, null).expectStatus().isBadRequest();

        assertThat(pedidosEnBd()).isZero();
    }

    @Test
    @DisplayName("una línea con productId nulo se rechaza con 400")
    void lineaConProductoNuloSeRechaza() {
        String sinProducto = """
                {"shippingAddressId":"%s","paymentMethod":"WALLET",
                 "items":[{"productId":null,"quantity":1}]}
                """.formatted(direccionId);

        checkout(sinProducto, null).expectStatus().isBadRequest();

        assertThat(pedidosEnBd()).isZero();
    }

    @Test
    @DisplayName("un producto inexistente devuelve 404 y no crea pedido")
    void productoInexistenteDevuelveCuatrocientosCuatro() {
        String otroProducto = """
                {"shippingAddressId":"%s","paymentMethod":"WALLET",
                 "items":[{"productId":"%s","quantity":1}]}
                """.formatted(direccionId, UUID.randomUUID());

        checkout(otroProducto, null).expectStatus().isNotFound();

        assertThat(pedidosEnBd()).isZero();
        assertThat(saldoEnBd()).isEqualTo(SALDO_HOLGADO);
    }

    @Test
    @DisplayName("el tope de líneas por pedido es 100: exactamente 100 se aceptan y suman bien")
    void cienLineasSeAceptanYSumanBien() {
        checkout(pedidoDeVariasLineas(LINEAS_MAXIMAS), null).expectStatus().isCreated();

        assertThat(totalCobradoEnBd()).isEqualTo(LINEAS_MAXIMAS * UNIDAD_CENTS + ENVIO_CENTS);
    }

    @Test
    @DisplayName("101 líneas se rechazan con 400 y no crean pedido")
    void cientoUnaLineasSeRechazan() {
        checkout(pedidoDeVariasLineas(LINEAS_MAXIMAS + 1), null).expectStatus().isBadRequest();

        assertThat(pedidosEnBd()).isZero();
    }

    /* ==================================================================================
     *  G · Saldo del monedero
     * ================================================================================== */

    @Test
    @DisplayName("saldo insuficiente devuelve 422 y NO deja pedido creado ni cargo alguno")
    void saldoInsuficienteDevuelve422YNoCreaPedido() {
        int total = 3 * UNIDAD_CENTS + ENVIO_CENTS;
        fijarSaldo(total - 1);

        checkout(pedidoDe(3), null).expectStatus().isEqualTo(422);

        assertThat(pedidosEnBd()).as("un pedido que no se ha podido cobrar no puede quedar creado").isZero();
        assertThat(saldoEnBd()).isEqualTo(total - 1L);
        assertThat(cargosAlMonedero()).isZero();
    }

    @Test
    @DisplayName("con el saldo EXACTAMENTE igual al total el pedido sí se cobra y el monedero queda a cero")
    void saldoExactamenteIgualAlTotalSiCobra() {
        int total = 3 * UNIDAD_CENTS + ENVIO_CENTS;
        fijarSaldo(total);

        JsonNode pedido = cuerpo(checkout(pedidoDe(3), null).expectStatus().isCreated());

        assertThat(centimos(pedido, "total")).isEqualTo(total);
        assertThat(saldoEnBd()).as("el borde es «menor que», no «menor o igual»").isZero();
    }

    @Test
    @DisplayName("con el saldo a cero el checkout se rechaza con 422")
    void saldoAceroSeRechaza() {
        fijarSaldo(0);

        checkout(pedidoDe(1), null).expectStatus().isEqualTo(422);

        assertThat(pedidosEnBd()).isZero();
    }

    @Test
    @DisplayName("un segundo pedido que ya no cabe en el saldo restante se rechaza y no deja el saldo negativo")
    void elSegundoPedidoQueNoCabeSeRechaza() {
        int total = UNIDAD_CENTS + ENVIO_CENTS;
        fijarSaldo(total);

        checkout(pedidoDe(1), null).expectStatus().isCreated();
        checkout(pedidoDe(1), null).expectStatus().isEqualTo(422);

        assertThat(saldoEnBd()).isZero();
        assertThat(pedidosEnBd()).as("el segundo pedido revierte entero").isEqualTo(1);
        assertThat(cargosAlMonedero()).isEqualTo(total);
    }

    @Test
    @DisplayName("sin monedero abierto el checkout no cobra nada: 404 y ningún pedido")
    void sinMonederoNoSeCobra() {
        jdbcTemplate.update("DELETE FROM wallet WHERE user_id = ?", userId);

        checkout(pedidoDe(1), null).expectStatus().isNotFound();

        assertThat(pedidosEnBd()).isZero();
    }

    /* ==================================================================================
     *  H · Idempotencia
     * ================================================================================== */

    @Test
    @DisplayName("la misma clave de idempotencia dos veces deja UN solo pedido y UN solo cobro")
    void mismaClaveDeIdempotenciaUnPedidoYUnCobro() {
        int total = 3 * UNIDAD_CENTS + ENVIO_CENTS;
        String clave = "cert-idem-" + UUID.randomUUID();

        JsonNode primero = cuerpo(checkout(pedidoDe(3), clave).expectStatus().isCreated());
        JsonNode segundo = cuerpo(checkout(pedidoDe(3), clave).expectStatus().isCreated());

        assertThat(segundo.get("id").asText()).isEqualTo(primero.get("id").asText());
        assertThat(pedidosEnBd()).isEqualTo(1);
        assertThat(centimos(segundo, "total")).isEqualTo(total);
        assertThat(cargosAlMonedero()).as("el reintento no puede cobrar dos veces").isEqualTo(total);
        assertThat(saldoEnBd()).isEqualTo(SALDO_HOLGADO - total);
    }

    @Test
    @DisplayName("reenviar la misma clave cinco veces sigue dejando un solo pedido y un solo cobro")
    void reenviarLaMismaClaveCincoVecesNoDuplica() {
        int total = UNIDAD_CENTS + ENVIO_CENTS;
        String clave = "cert-idem-" + UUID.randomUUID();

        for (int i = 0; i < 5; i++) {
            checkout(pedidoDe(1), clave).expectStatus().isCreated();
        }

        assertThat(pedidosEnBd()).isEqualTo(1);
        assertThat(cargosAlMonedero()).isEqualTo(total);
        assertThat(saldoEnBd()).isEqualTo(SALDO_HOLGADO - total);
    }

    @Test
    @DisplayName("claves de idempotencia distintas son pedidos distintos y se cobran los dos")
    void clavesDistintasCreanDosPedidos() {
        int total = UNIDAD_CENTS + ENVIO_CENTS;

        JsonNode primero = cuerpo(checkout(pedidoDe(1), "clave-a").expectStatus().isCreated());
        JsonNode segundo = cuerpo(checkout(pedidoDe(1), "clave-b").expectStatus().isCreated());

        assertThat(segundo.get("id").asText()).isNotEqualTo(primero.get("id").asText());
        assertThat(pedidosEnBd()).isEqualTo(2);
        assertThat(cargosAlMonedero()).isEqualTo(2L * total);
    }

    @Test
    @DisplayName("sin clave de idempotencia cada llamada es un pedido nuevo y se cobra cada uno")
    void sinClaveCadaLlamadaEsUnPedido() {
        int total = UNIDAD_CENTS + ENVIO_CENTS;

        checkout(pedidoDe(1), null).expectStatus().isCreated();
        checkout(pedidoDe(1), null).expectStatus().isCreated();

        assertThat(pedidosEnBd()).isEqualTo(2);
        assertThat(cargosAlMonedero()).isEqualTo(2L * total);
    }

    @Test
    @DisplayName("sin clave de idempotencia no hay reutilización: dos llamadas, dos pedidos")
    void sinClaveNoHayReutilizacion() {
        // El caso "clave EN BLANCO" no se puede ejercitar por HTTP: un valor de cabecera vacío o de solo
        // espacios lo rechaza el propio cliente antes de enviarlo (HTTP no admite espacios al principio ni
        // al final del valor), así que la petición ni sale. La comprobación de blank en el servidor queda
        // como defensa en profundidad, para llamadas que no vengan por la red. Lo que SÍ es alcanzable, y
        // es lo que de verdad usa un cliente, es no mandar la cabecera: aquí se comprueba que entonces
        // cada llamada crea su propio pedido.
        checkout(pedidoDe(1), null).expectStatus().isCreated();
        checkout(pedidoDe(1), null).expectStatus().isCreated();

        assertThat(pedidosEnBd()).isEqualTo(2);
    }

    @Test
    @DisplayName("la clave de idempotencia es de cada usuario: la de otro no le devuelve su pedido")
    void laClaveDeIdempotenciaNoSeCruzaEntreUsuarios() {
        String clave = "clave-compartida";
        JsonNode mio = cuerpo(checkout(pedidoDe(1), clave).expectStatus().isCreated());

        UUID otroId = UUID.randomUUID();
        String otroToken = jwt.userToken(otroId, "otro@nx036.local", "USER");
        insertarUsuario(otroId, "otro@nx036.local");
        acreditarWallet(otroId, SALDO_HOLGADO);
        UUID otraDireccion = crearDireccion(PAIS, otroToken);

        String cuerpoOtro = """
                {"shippingAddressId":"%s","paymentMethod":"WALLET",
                 "items":[{"productId":"%s","quantity":1}]}
                """.formatted(otraDireccion, productId);
        JsonNode suyo = cuerpo(checkoutComo(otroToken, cuerpoOtro, clave).expectStatus().isCreated());

        assertThat(suyo.get("id").asText()).isNotEqualTo(mio.get("id").asText());
        assertThat(pedidosEnBd()).isEqualTo(2);
    }

    /* ==================================================================================
     *  I · Dirección y destino
     * ================================================================================== */

    @Test
    @DisplayName("un destino sin cobertura de transporte se rechaza con 422 antes de cobrar")
    void destinoSinCoberturaSeRechaza() {
        UUID direccionFrancia = crearDireccion(PAIS_SIN_COBERTURA);
        String cuerpoFrancia = """
                {"shippingAddressId":"%s","paymentMethod":"WALLET",
                 "items":[{"productId":"%s","quantity":1}]}
                """.formatted(direccionFrancia, productId);

        checkoutComo(token, cuerpoFrancia, null).expectStatus().isEqualTo(422);

        assertThat(pedidosEnBd()).isZero();
        assertThat(saldoEnBd()).isEqualTo(SALDO_HOLGADO);
    }

    @Test
    @DisplayName("sin dirección de envío el checkout se rechaza con 422")
    void sinDireccionDeEnvioSeRechaza() {
        String sinDireccion = """
                {"paymentMethod":"WALLET","items":[{"productId":"%s","quantity":1}]}
                """.formatted(productId);

        checkout(sinDireccion, null).expectStatus().isEqualTo(422);

        assertThat(pedidosEnBd()).isZero();
    }

    @Test
    @DisplayName("una dirección inexistente devuelve 404")
    void direccionInexistenteDevuelveCuatrocientosCuatro() {
        String otraDireccion = """
                {"shippingAddressId":"%s","paymentMethod":"WALLET",
                 "items":[{"productId":"%s","quantity":1}]}
                """.formatted(UUID.randomUUID(), productId);

        checkout(otraDireccion, null).expectStatus().isNotFound();

        assertThat(pedidosEnBd()).isZero();
    }

    @Test
    @DisplayName("la dirección de OTRO usuario devuelve 404, no 403: no se confirma que exista")
    void laDireccionDeOtroUsuarioDevuelveCuatrocientosCuatro() {
        UUID otroId = UUID.randomUUID();
        String otroToken = jwt.userToken(otroId, "ajeno@nx036.local", "USER");
        insertarUsuario(otroId, "ajeno@nx036.local");
        UUID direccionAjena = crearDireccion(PAIS, otroToken);

        String conDireccionAjena = """
                {"shippingAddressId":"%s","paymentMethod":"WALLET",
                 "items":[{"productId":"%s","quantity":1}]}
                """.formatted(direccionAjena, productId);

        checkout(conDireccionAjena, null).expectStatus().isNotFound();

        assertThat(pedidosEnBd()).isZero();
        assertThat(saldoEnBd()).isEqualTo(SALDO_HOLGADO);
    }

    /* ==================================================================================
     *  J · Producto no vendible
     * ================================================================================== */

    @Test
    @DisplayName("un producto pausado no se puede comprar aunque siga en el carrito: 422")
    void productoPausadoNoSePuedeComprar() {
        jdbcTemplate.update("UPDATE product SET status = 'PAUSED' WHERE id = ?", productId);

        checkout(pedidoDe(1), null).expectStatus().isEqualTo(422);

        assertThat(pedidosEnBd()).isZero();
        assertThat(saldoEnBd()).isEqualTo(SALDO_HOLGADO);
    }

    @Test
    @DisplayName("un producto archivado tampoco se puede comprar: 422")
    void productoArchivadoNoSePuedeComprar() {
        jdbcTemplate.update("UPDATE product SET status = 'ARCHIVED' WHERE id = ?", productId);

        checkout(pedidoDe(1), null).expectStatus().isEqualTo(422);

        assertThat(pedidosEnBd()).isZero();
    }

    @Test
    @DisplayName("un producto sin precio no se puede cobrar: 422 y sin pedido")
    void productoSinPrecioNoSePuedeCobrar() {
        jdbcTemplate.update("UPDATE product SET base_price = NULL WHERE id = ?", productId);

        checkout(pedidoDe(1), null).expectStatus().isEqualTo(422);

        assertThat(pedidosEnBd()).isZero();
    }

    @Test
    @DisplayName("una variante inexistente devuelve 404 sin crear pedido")
    void varianteInexistenteDevuelveCuatrocientosCuatro() {
        String conVarianteRota = """
                {"shippingAddressId":"%s","paymentMethod":"WALLET",
                 "items":[{"productId":"%s","variantId":"%s","quantity":1}]}
                """.formatted(direccionId, productId, UUID.randomUUID());

        checkout(conVarianteRota, null).expectStatus().isNotFound();

        assertThat(pedidosEnBd()).isZero();
    }

    @Test
    @DisplayName("con variante, el precio que se cobra es el de LA VARIANTE, no el del producto")
    void elPrecioDeLaVarianteManda() {
        UUID varianteId = insertarVariante(productId, "20.0000");

        String conVariante = """
                {"shippingAddressId":"%s","paymentMethod":"WALLET",
                 "items":[{"productId":"%s","variantId":"%s","quantity":2}]}
                """.formatted(direccionId, productId, varianteId);

        JsonNode pedido = cuerpo(checkoutComo(token, conVariante, null).expectStatus().isCreated());

        assertThat(centimos(pedido, "subtotal")).isEqualTo(2 * 2000);
        assertThat(centimos(pedido, "total")).isEqualTo(2 * 2000 + ENVIO_CENTS);
    }

    /* ==================================================================================
     *  K · Autorización
     * ================================================================================== */

    @Test
    @DisplayName("el checkout sin token devuelve 401 y no toca nada")
    void checkoutSinTokenDevuelve401() {
        client.post().uri(CHECKOUT).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(pedidoDe(1)).exchange().expectStatus().isUnauthorized();

        assertThat(pedidosEnBd()).isZero();
        assertThat(saldoEnBd()).isEqualTo(SALDO_HOLGADO);
    }

    @Test
    @DisplayName("el pedido de otro usuario no se puede leer: 404, sin filtrar que existe")
    void elPedidoDeOtroUsuarioNoSeVe() {
        JsonNode mio = cuerpo(checkout(pedidoDe(1), null).expectStatus().isCreated());

        UUID otroId = UUID.randomUUID();
        String otroToken = jwt.userToken(otroId, "curioso@nx036.local", "USER");
        insertarUsuario(otroId, "curioso@nx036.local");

        client.get().uri(MIS_PEDIDOS + "/" + mio.get("id").asText())
                .header(HttpHeaders.AUTHORIZATION, bearer(otroToken)).exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("el detalle del propio pedido devuelve los mismos importes que la respuesta del checkout")
    void elDetalleDelPedidoDevuelveLosMismosImportes() {
        JsonNode creado = cuerpo(checkout(pedidoDe(3), null).expectStatus().isCreated());

        JsonNode detalle = cuerpo(client.get().uri(MIS_PEDIDOS + "/" + creado.get("id").asText())
                .header(HttpHeaders.AUTHORIZATION, bearer(token)).exchange().expectStatus().isOk());

        assertThat(centimos(detalle, "subtotal")).isEqualTo(centimos(creado, "subtotal"));
        assertThat(centimos(detalle, "shipping")).isEqualTo(centimos(creado, "shipping"));
        assertThat(centimos(detalle, "tax")).isEqualTo(centimos(creado, "tax"));
        assertThat(centimos(detalle, "discount")).isEqualTo(centimos(creado, "discount"));
        assertThat(centimos(detalle, "total")).isEqualTo(centimos(creado, "total"));
    }

    /* ==================================================================================
     *  L · La cesta se vacía al quedar el pedido PAGADO (y solo entonces)
     * ================================================================================== */

    /**
     * Con saldo el cobro es inmediato: el pedido nace PAGADO y lo comprado desaparece de la cesta sin que
     * el cliente tenga que limpiarla por su cuenta (si lo hiciera él, un cierre de pestaña entre el cobro
     * y la limpieza dejaría la compra repetida en la cesta de todos sus dispositivos).
     */
    @Test
    @DisplayName("pagando con saldo, la cesta del servidor queda vacía tras el pedido")
    void pagandoConSaldoLaCestaQuedaVacia() {
        meterEnLaCesta(productId, 3);
        assertThat(lineasEnLaCesta()).hasSize(1);

        JsonNode pedido = cuerpo(checkout(pedidoDe(3), null).expectStatus().isCreated());

        assertThat(pedido.get("status").asText()).isEqualTo("PAID");
        assertThat(lineasEnLaCesta()).as("lo comprado ya no está en la cesta").isEmpty();
        assertThat(lineasDeLaCestaEnBd()).isZero();
    }

    /**
     * El caso que obliga a esperar al PAGADO: con tarjeta el pedido nace PENDIENTE y el dinero se cobra
     * fuera —puede no llegar nunca—. Vaciar aquí dejaría a la persona sin pedido y sin cesta.
     */
    @Test
    @DisplayName("con pago externo el pedido nace PENDIENTE y la cesta sigue intacta")
    void conPagoExternoLaCestaSigueIntacta() {
        meterEnLaCesta(productId, 2);

        String conTarjeta = """
                {"shippingAddressId":"%s","paymentMethod":"CARD",
                 "items":[{"productId":"%s","quantity":2}]}
                """.formatted(direccionId, productId);
        JsonNode pedido = cuerpo(checkout(conTarjeta, null).expectStatus().isCreated());

        assertThat(pedido.get("status").asText()).isEqualTo("PENDING");
        assertThat(lineasEnLaCesta()).as("el dinero aún no ha llegado: la cesta no se toca").hasSize(1);
        assertThat(saldoEnBd()).as("con tarjeta el monedero no se toca").isEqualTo(SALDO_HOLGADO);
    }

    /** Quien tramita solo una parte de su cesta conserva el resto: se quita lo comprado, nada más. */
    @Test
    @DisplayName("comprando una sola línea, la otra sobrevive en la cesta")
    void comprandoUnaLineaLaOtraSobrevive() {
        UUID otroProducto = insertarProducto(PRECIO_BASE, "0");
        meterEnLaCesta(productId, 1);
        meterEnLaCesta(otroProducto, 1);
        assertThat(lineasEnLaCesta()).hasSize(2);

        cuerpo(checkout(pedidoDe(1), null).expectStatus().isCreated());

        List<Map<String, Object>> cesta = lineasEnLaCesta();
        assertThat(cesta).hasSize(1);
        assertThat(cesta.get(0)).containsEntry("productId", otroProducto.toString());
    }

    /* ==================================================================================
     *  Utilidades de la prueba
     * ================================================================================== */

    /** Sube una línea a la cesta del servidor tal como lo haría la web o la app. */
    private void meterEnLaCesta(UUID producto, int cantidad) {
        String linea = """
                {"productId":"%s","variantId":null,"sku":"SKU-1","slug":"producto","title":"Producto",
                 "image":"https://cdn.local/p.jpg","variantLabel":null,"unitPriceSource":10.00,
                 "sourceCurrency":"USD","quantity":%d,"moq":1,"unitPriceDisplay":10.00,
                 "displayCurrency":"USD","displaySymbol":"$"}
                """.formatted(producto, cantidad);
        client.put().uri(CESTA).header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(linea).exchange().expectStatus().isOk();
    }

    private List<Map<String, Object>> lineasEnLaCesta() {
        return client.get().uri(CESTA).header(HttpHeaders.AUTHORIZATION, bearer(token)).exchange()
                .expectStatus().isOk().expectBody(LINEAS_DE_CESTA).returnResult().getResponseBody();
    }

    private int lineasDeLaCestaEnBd() {
        Integer total = jdbcTemplate.queryForObject("SELECT count(*) FROM cart_item WHERE user_id = ?",
                Integer.class, userId);
        return total == null ? 0 : total;
    }

    /** Checkout del usuario de la prueba. {@code idem} nulo = sin cabecera de idempotencia. */
    /* ==================================================================================
     *  H · Pedido mínimo por producto (lote mixto)
     * ================================================================================== */

    /**
     * El mínimo se cumple sumando variantes distintas, como el lote mixto de 1688.
     *
     * <p>Dos colores de una unidad cada uno cubren un mínimo de dos: al proveedor solo le importa el
     * total. Exigirlo por variante obligaría a comprar el doble de lo necesario.
     */
    @Test
    @DisplayName("el pedido mínimo se cumple mezclando variantes distintas del mismo producto")
    void elMinimoSeCumpleMezclandoVariantes() {
        UUID lote = insertarProductoConMoq("10.00", 2);
        UUID rojo = insertarVariante(lote, "10.00");
        UUID azul = insertarVariante(lote, "10.00");
        String cuerpo = "{\"shippingAddressId\":\"" + direccionId + "\",\"paymentMethod\":\"WALLET\","
                + "\"items\":[{\"productId\":\"" + lote + "\",\"variantId\":\"" + rojo + "\",\"quantity\":1},"
                + "{\"productId\":\"" + lote + "\",\"variantId\":\"" + azul + "\",\"quantity\":1}]}";

        checkout(cuerpo, null).expectStatus().isCreated();
    }

    /** Por debajo del mínimo el pedido se rechaza, y antes de mover dinero. */
    @Test
    @DisplayName("por debajo del pedido mínimo el checkout se rechaza sin cobrar")
    void porDebajoDelMinimoSeRechaza() {
        UUID lote = insertarProductoConMoq("10.00", 3);
        long saldoAntes = saldoEnBd();
        String cuerpo = "{\"shippingAddressId\":\"" + direccionId + "\",\"paymentMethod\":\"WALLET\","
                + "\"items\":[{\"productId\":\"" + lote + "\",\"quantity\":2}]}";

        checkout(cuerpo, null).expectStatus().is4xxClientError();

        assertThat(saldoEnBd()).as("no se cobra un pedido que no llega al mínimo").isEqualTo(saldoAntes);
    }

    /** Justo en el mínimo: el borde exacto se acepta. */
    @Test
    @DisplayName("justo en el pedido mínimo el checkout pasa")
    void justoEnElMinimoSeAcepta() {
        UUID lote = insertarProductoConMoq("10.00", 3);
        String cuerpo = "{\"shippingAddressId\":\"" + direccionId + "\",\"paymentMethod\":\"WALLET\","
                + "\"items\":[{\"productId\":\"" + lote + "\",\"quantity\":3}]}";

        checkout(cuerpo, null).expectStatus().isCreated();
    }

    /**
     * El mínimo es de cada producto, no del pedido.
     *
     * <p>Dos productos con una unidad cada uno suman dos, pero ninguno llega a su propio mínimo:
     * contar el total del pedido dejaría pasar compras que el proveedor rechaza.
     */
    @Test
    @DisplayName("el mínimo no se comparte entre productos distintos")
    void elMinimoNoSeComparteEntreProductos() {
        UUID uno = insertarProductoConMoq("10.00", 2);
        UUID otro = insertarProductoConMoq("10.00", 2);
        String cuerpo = "{\"shippingAddressId\":\"" + direccionId + "\",\"paymentMethod\":\"WALLET\","
                + "\"items\":[{\"productId\":\"" + uno + "\",\"quantity\":1},"
                + "{\"productId\":\"" + otro + "\",\"quantity\":1}]}";

        checkout(cuerpo, null).expectStatus().is4xxClientError();
    }

    private WebTestClient.ResponseSpec checkout(String cuerpo, String idem) {
        return checkoutComo(token, cuerpo, idem);
    }

    private WebTestClient.ResponseSpec checkoutComo(String tokenDeQuien, String cuerpo, String idem) {
        WebTestClient.RequestBodySpec peticion = client.post().uri(CHECKOUT)
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenDeQuien))
                .contentType(MediaType.APPLICATION_JSON);
        // El checkout EXIGE la clave: identifica el intento de compra, no la petición. Sin ella el servidor
        // responde 400 en vez de crear un segundo pedido. Nueva por llamada salvo que la prueba pase la
        // suya, que es como se ejercita el reenvío del mismo intento.
        peticion = peticion.header(CABECERA_IDEMPOTENCIA, idem != null ? idem : UUID.randomUUID().toString());
        return peticion.bodyValue(cuerpo).exchange();
    }

    /** Vista previa del checkout (el resumen que ve el comprador antes de pagar). */
    private WebTestClient.ResponseSpec cotizar(int cantidad, String cupon) {
        String cuerpo = """
                {"country":"%s","region":null,"couponCode":%s,
                 "items":[{"productId":"%s","quantity":%d}]}
                """.formatted(PAIS, cupon == null ? "null" : "\"" + cupon + "\"", productId, cantidad);
        return client.post().uri(COTIZAR).header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(cuerpo).exchange();
    }

    private String pedidoDe(int cantidad) {
        return pedidoDe(cantidad, null);
    }

    private String pedidoDe(int cantidad, String cupon) {
        String cuponJson = cupon == null ? "" : ",\"couponCode\":\"" + cupon + "\"";
        return "{\"shippingAddressId\":\"" + direccionId + "\",\"paymentMethod\":\"WALLET\"" + cuponJson
                + ",\"items\":[{\"productId\":\"" + productId + "\",\"quantity\":" + cantidad + "}]}";
    }

    /** Pedido con {@code lineas} líneas de una unidad cada una, para tantear el tope de líneas. */
    private String pedidoDeVariasLineas(int lineas) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < lineas; i++) {
            if (i > 0) {
                items.append(',');
            }
            items.append("{\"productId\":\"").append(productId).append("\",\"quantity\":1}");
        }
        return "{\"shippingAddressId\":\"" + direccionId + "\",\"paymentMethod\":\"WALLET\",\"items\":["
                + items + "]}";
    }

    private JsonNode cuerpo(WebTestClient.ResponseSpec respuesta) {
        String texto = respuesta.expectBody(String.class).returnResult().getResponseBody();
        try {
            return JSON.readTree(texto == null ? "{}" : texto);
        } catch (JacksonException e) {
            throw new IllegalStateException("Respuesta no es JSON: " + texto, e);
        }
    }

    /** Importe de un campo monetario del pedido (viene en unidades, p. ej. 35.0000), en céntimos. */
    private static int centimos(JsonNode nodo, String campo) {
        JsonNode valor = nodo.get(campo);
        assertThat(valor).as("falta el campo monetario «%s» en la respuesta: %s", campo, nodo).isNotNull();
        return valor.decimalValue().movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    /**
     * Céntimos de un importe YA formateado por el backend ("$35.00"). Sin fila en {@code currency_rate} el
     * formateador cae al locale en-US, así que el separador de miles es la coma y el decimal el punto.
     */
    private static int centimosDeTexto(String formateado) {
        String limpio = formateado.replace(",", "").replaceAll("[^0-9.\\-]", "");
        return new BigDecimal(limpio).movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    /* ---------- Siembra del escenario ---------- */

    private void insertarUsuario(UUID id, String email) {
        jdbcTemplate.update("INSERT INTO users (id, email, role, active, language, created_at, updated_at)"
                + " VALUES (?, ?, 'USER', true, 'es', now(), now())", id, email);
    }

    /**
     * El saldo se siembra en la tabla: la recarga real exige pasarela de pago y aquí lo que se prueba es
     * el cobro del pedido, no el alta de fondos.
     */
    private void acreditarWallet(UUID id, long centimos) {
        jdbcTemplate.update("INSERT INTO wallet (id, user_id, balance_usd_cents, hold_usd_cents,"
                + " currency_default, status, created_at, updated_at)"
                + " VALUES (gen_random_uuid(), ?, ?, 0, 'USD', 'ACTIVE', now(), now())", id, centimos);
    }

    private void fijarSaldo(long centimos) {
        jdbcTemplate.update("UPDATE wallet SET balance_usd_cents = ?, updated_at = now() WHERE user_id = ?",
                centimos, userId);
    }

    /** Cobertura del transportista. Con {@code per_kg_cents = 0} el porte es plano y no depende del peso. */
    private void insertarZona(String pais, int baseCents) {
        jdbcTemplate.update("INSERT INTO cainiao_shipping_zone (id, country_code, country_name, zone,"
                + " base_cents, per_kg_cents, eta_min_days, eta_max_days, enabled)"
                + " VALUES (gen_random_uuid(), ?, ?, 'EU', ?, 0, 5, 10, true)", pais, pais, baseCents);
    }

    /**
     * Producto tarifado en USD para que la conversión de divisa sea la identidad y el número esperado se
     * pueda escribir a mano. {@code shippingCny} es el porte propio del producto (en su moneda, aquí USD):
     * el margen NO se le aplica, así que sirve para separar la BASE —que es el suelo de las rebajas— del
     * precio de tarifa.
     */
    private UUID insertarProducto(String basePrice, String shippingCny) {
        UUID id = UUID.randomUUID();
        String sufijo = id.toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO product (id, slug, external_id, source, title_zh, status, moq,"
                + " base_price, currency, shipping_cny, iva_cny, weight_grams, created_at, updated_at)"
                + " VALUES (?, ?, ?, 'TEST', 'Producto de prueba', 'ACTIVE', 1, ?::numeric,"
                + " 'USD', ?::numeric, 0, 500, now(), now())",
                id, "producto-" + sufijo, "ext-" + sufijo, basePrice, shippingCny);
        return id;
    }

    /** Como {@link #insertarProducto}, pero con pedido mínimo: el producto se vende por lotes. */
    private UUID insertarProductoConMoq(String basePrice, int moq) {
        UUID id = UUID.randomUUID();
        String sufijo = id.toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO product (id, slug, external_id, source, title_zh, status, moq,"
                + " base_price, currency, shipping_cny, iva_cny, weight_grams, created_at, updated_at)"
                + " VALUES (?, ?, ?, 'TEST', 'Producto por lotes', 'ACTIVE', ?, ?::numeric,"
                + " 'USD', 0, 0, 500, now(), now())",
                id, "lote-" + sufijo, "extlote-" + sufijo, moq, basePrice);
        return id;
    }

    private UUID insertarVariante(UUID producto, String precio) {
        UUID id = UUID.randomUUID();
        String sufijo = id.toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO product_variant (id, product_id, external_id, sku, title, price,"
                + " stock, active, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, 'Variante', ?::numeric, 100, true, now(), now())",
                id, producto, "var-" + sufijo, "SKU-" + sufijo, precio);
        return id;
    }

    private void insertarIva(String pais, int rateBps) {
        jdbcTemplate.update("INSERT INTO country_tax_rate (id, country_code, label, rate_bps, active,"
                + " created_at, updated_at) VALUES (gen_random_uuid(), ?, 'IVA', ?, true, now(), now())",
                pais, rateBps);
    }

    /** Rebaja AUTOMÁTICA (sin código): la que se anuncia en el escaparate y compite contra el cupón. */
    private void insertarRebajaAutomatica(String nombre, String porcentaje) {
        jdbcTemplate.update("INSERT INTO promotion (id, name, code, kind, scope, percent_off, active,"
                + " priority, used_count, created_at)"
                + " VALUES (gen_random_uuid(), ?, NULL, 'SEASONAL', 'ALL', ?::numeric, true, 0, 0, now())",
                nombre, porcentaje);
    }

    /**
     * Cupón (con código): solo descuenta si el cliente lo teclea. Uno de {@code porcentaje} o
     * {@code importeCents} debe venir a nulo — la base lo exige con una restricción.
     *
     * <p>Los parámetros van como texto con un cast explícito ({@code ?::numeric}) porque el driver de
     * PostgreSQL no puede deducir el tipo de un parámetro nulo y aborta la sentencia.
     */
    private void insertarCupon(String codigo, String porcentaje, Integer importeCents, Instant desde,
            Instant hasta, boolean activo, Integer maxUsos, int usosHechos, Integer pedidoMinimoCents) {
        jdbcTemplate.update("INSERT INTO promotion (id, name, code, kind, scope, percent_off,"
                + " amount_off_cents, starts_at, ends_at, active, priority, max_uses, used_count,"
                + " min_order_cents, created_at)"
                + " VALUES (gen_random_uuid(), ?, ?, 'COUPON', 'ALL', ?::numeric, ?::integer,"
                + " ?::timestamptz, ?::timestamptz, ?, 0, ?::integer, ?, ?::integer, now())",
                "Cupón " + codigo, codigo, porcentaje, comoTexto(importeCents), comoTexto(desde),
                comoTexto(hasta), activo, comoTexto(maxUsos), usosHechos, comoTexto(pedidoMinimoCents));
    }

    /** Valor como texto para los parámetros con cast, o {@code null} si no hay valor. */
    private static String comoTexto(Object valor) {
        return valor == null ? null : String.valueOf(valor);
    }

    private UUID crearDireccion(String pais) {
        return crearDireccion(pais, token);
    }

    private UUID crearDireccion(String pais, String tokenDeQuien) {
        String cuerpo = """
                {"fullName":"Comprador de prueba","phone":"+34600000000","line1":"Gran Via 1",
                 "city":"Madrid","postalCode":"28013","country":"%s","isDefault":true}
                """.formatted(pais);
        JsonNode creada = cuerpo(client.post().uri(DIRECCIONES)
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenDeQuien))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(cuerpo).exchange()
                .expectStatus().isCreated());
        return UUID.fromString(creada.get("id").asText());
    }

    /* ---------- Lecturas directas de la base, para contrastar lo que dice la API ---------- */

    private int pedidosEnBd() {
        Integer total = jdbcTemplate.queryForObject("SELECT count(*) FROM customer_order", Integer.class);
        return total == null ? 0 : total;
    }

    private int totalCobradoEnBd() {
        Integer total = jdbcTemplate.queryForObject("SELECT total_cents FROM customer_order", Integer.class);
        return total == null ? 0 : total;
    }

    private long saldoEnBd() {
        Long saldo = jdbcTemplate.queryForObject("SELECT balance_usd_cents FROM wallet WHERE user_id = ?",
                Long.class, userId);
        return saldo == null ? 0L : saldo;
    }

    /** Suma de lo DEBITADO al monedero por pagos de pedido (el apunte va en negativo; aquí en positivo). */
    private long cargosAlMonedero() {
        Long cargado = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(-t.amount_usd_cents), 0) FROM wallet_transaction t"
                        + " JOIN wallet w ON w.id = t.wallet_id"
                        + " WHERE w.user_id = ? AND t.kind = 'PAYMENT'",
                Long.class, userId);
        return cargado == null ? 0L : cargado;
    }
}
