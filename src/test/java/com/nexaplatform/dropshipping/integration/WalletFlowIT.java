package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.config.BaseIntegration;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Certificación del DINERO del monedero por HTTP, contra un Postgres real (Testcontainers).
 *
 * <p>Por qué existe: el 14-ago-2026 ocho checkouts simultáneos con saldo para uno dejaron cuatro
 * pedidos pagados cobrando un solo débito. Sobrevivió a cuatro pentests porque todas las pruebas del
 * monedero eran secuenciales y con dobles: nadie comprobaba nunca el LIBRO MAYOR ni los BORDES. Aquí
 * cada importe se comprueba EXACTO, al céntimo y calculado a mano; un 200 con el número equivocado es
 * un fallo, no un aprobado.
 *
 * <p>Regla de la casa en esta clase: todo lo que tiene endpoint se ataca por HTTP. Los dos únicos
 * movimientos sin endpoint propio ({@code charge} y {@code refund}, que solo se alcanzan desde el
 * checkout y desde el reembolso de un pedido) se atacan sobre el MÉTODO REAL del caso de uso, nunca
 * sobre un SQL escrito para la ocasión: si alguien vuelve a mover el saldo con un
 * leer-comprobar-escribir, estas pruebas se caen. El flujo completo de compra (catálogo, checkout,
 * reembolso) se certifica en {@code AffiliateFlowIT}, que sí lo necesita entero.
 */
class WalletFlowIT extends BaseIntegration {

    /* ---------------------------- Rutas reales del API ---------------------------- */

    private static final String ME_WALLET = "/api/me/wallet";
    private static final String ME_WALLET_TX = "/api/me/wallet/transactions?page=0&size=100";
    private static final String ME_RECHARGE = "/api/me/wallet/recharge";
    private static final String ME_CONFIRM_MOCK = "/api/me/wallet/confirm-mock?paymentId=%s";
    private static final String ME_CONFIRM_REAL = "/api/me/wallet/recharge/%s/confirm";
    private static final String ADMIN_TOPUP = "/api/admin/wallets/%s/topup";
    private static final String ADMIN_ADJUST = "/api/admin/wallets/%s/adjust";
    private static final String ADMIN_DETAIL = "/api/admin/wallets/%s";

    /** Cuerpos JSON genéricos: la respuesta cambia de forma según el caso (resultado o error). */
    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    private static final int NO_PROCESABLE = 422;
    private static final String CAMPO_IMPORTE = "amountCents";
    private static final String CAMPO_IMPORTE_USD = "amountUsdCents";
    private static final String CAMPO_ID_PAGO = "paymentId";
    private static final String CAMPO_SALDO_POSTERIOR = "balanceAfter";
    private static final String CAMPO_DESCRIPCION = "description";
    private static final String CLAVE_IDEM = "idempotencyKey";
    private static final String METODO_TARJETA = "CARD";
    private static final String DIVISA_MOSTRADA = "currencyDisplay";
    private static final String IMPORTE_MOSTRADO = "amountDisplay";

    @Autowired
    private WalletUseCase walletUseCase;

    @Autowired
    private CurrencyRateService currencyRateService;

    private UUID clienteId;
    private String clienteToken;
    private UUID intrusoId;
    private String intrusoToken;
    private String adminToken;

    /**
     * Tres actores en cada prueba: el dueño del monedero, un segundo cliente (para cruzar la
     * autorización) y un administrador. Se crean a mano en la tabla {@code users} porque lo que se
     * certifica aquí es el dinero, no el alta de la cuenta.
     */
    @BeforeEach
    void prepararActores() {
        clienteId = crearUsuario("cliente-" + UUID.randomUUID() + "@example.com", "USER");
        clienteToken = jwt.userToken(clienteId, "cliente@example.com", "USER");
        intrusoId = crearUsuario("intruso-" + UUID.randomUUID() + "@example.com", "USER");
        intrusoToken = jwt.userToken(intrusoId, "intruso@example.com", "USER");
        adminToken = jwt.userToken(crearUsuario("admin-" + UUID.randomUUID() + "@example.com", "ADMIN"),
                "admin@example.com", "ADMIN");
    }

    /* ============================== Libro mayor ============================== */

    /**
     * El invariante que nadie estaba comprobando: el libro mayor tiene que CUADRAR. Para cada apunte,
     * {@code balance_after_cents} es el saldo real tras aplicarlo, y la suma de todos los apuntes es el
     * saldo final. Si un movimiento vuelve a pisar el saldo escrito por otro, el descuadre aparece aquí
     * aunque el saldo final parezca razonable.
     */
    @Test
    @DisplayName("el libro mayor cuadra: cada saldo posterior es el real y la suma de apuntes es el saldo final")
    void elLibroMayorCuadraConElSaldoReal() {
        sembrarSaldo(clienteId, 0L);

        // 10.000 + 1 − 2.500 + 5.000 = 12.501 céntimos. Importes distintos a propósito: así ninguna suma
        // parcial coincide con otra y un apunte colocado en el sitio equivocado no puede pasar inadvertido.
        abonoAdmin(clienteId, 10_000L, "alta manual", "libro-1");
        ajusteAdmin(clienteId, 1L, "propina", "libro-2");
        ajusteAdmin(clienteId, -2_500L, "penalización", "libro-3");
        recargarYConfirmar(clienteToken, 5_000L);

        long saldoFinal = saldoPorHttp(clienteToken);
        assertThat(saldoFinal).as("saldo final calculado a mano: 10000 + 1 − 2500 + 5000").isEqualTo(12_501L);

        List<Map<String, Object>> apuntes = movimientosAntiguoPrimero(clienteToken);
        assertThat(apuntes).as("cuatro movimientos, uno por operación").hasSize(4);

        long acumulado = 0L;
        for (Map<String, Object> apunte : apuntes) {
            acumulado += numero(apunte, CAMPO_IMPORTE_USD);
            assertThat(numero(apunte, "balanceAfterCents"))
                    .as("el saldo posterior del apunte %s tiene que ser el saldo real tras aplicarlo", apunte.get("id"))
                    .isEqualTo(acumulado);
        }
        assertThat(acumulado).as("la suma de los apuntes es el saldo final").isEqualTo(saldoFinal);
        assertThat(saldoEnBd(clienteId)).as("y el saldo de la base coincide con el que devuelve el API")
                .isEqualTo(saldoFinal);
    }

    /* ============================== Bordes del saldo ============================== */

    /**
     * El borde EXACTO de la comprobación "saldo suficiente": dejar el monedero a cero está permitido.
     * Es el valor que más veces se programa mal (un {@code <} donde va un {@code <=}) y el que más caro
     * sale, porque o bloquea un cobro legítimo o autoriza uno que no cabe.
     */
    @Test
    @DisplayName("un cobro que deja el saldo exactamente a cero se permite")
    void cobroQueDejaElSaldoExactamenteACero() {
        sembrarSaldo(clienteId, 7_325L);

        Map<String, Object> resultado = ajusteAdmin(clienteId, -7_325L, "cobro al límite", "cero-1");

        assertThat(numero(resultado, CAMPO_SALDO_POSTERIOR)).as("el apunte deja constancia del saldo cero").isZero();
        assertThat(saldoPorHttp(clienteToken)).as("saldo exactamente a cero").isZero();
        assertThat(saldoEnBd(clienteId)).isZero();
    }

    /**
     * Un céntimo más de lo que hay: se rechaza y —lo importante— el saldo NO se mueve. Un rechazo que
     * deja el saldo tocado es un descuadre silencioso, que es exactamente lo que se busca aquí.
     */
    @Test
    @DisplayName("un cobro de un céntimo más que el saldo se rechaza y el saldo no cambia")
    void cobroDeUnCentimoMasQueElSaldoSeRechaza() {
        sembrarSaldo(clienteId, 7_325L);

        peticion(HttpMethod.POST, String.format(ADMIN_ADJUST, clienteId), adminToken,
                Map.of(CAMPO_IMPORTE, -7_326L, CAMPO_DESCRIPCION, "un céntimo de más", CLAVE_IDEM, "borde-1"), null)
                .expectStatus().isEqualTo(NO_PROCESABLE);

        assertThat(saldoPorHttp(clienteToken)).as("el saldo no se mueve tras un rechazo").isEqualTo(7_325L);
        assertThat(numeroApuntes(clienteId)).as("un rechazo no deja apunte en el libro mayor").isZero();
    }

    /**
     * El mismo borde sobre el cargo del CHECKOUT ({@code WalletUseCase.charge}), que es por donde se
     * escapó el dinero. No tiene endpoint propio —solo se llega desde el checkout—, así que se ataca el
     * método real del caso de uso contra el Postgres real. Un SQL escrito aquí solo demostraría que
     * PostgreSQL sabe restar, que nunca estuvo en duda.
     */
    @Test
    @DisplayName("el cargo del checkout admite el importe exacto del saldo y rechaza un céntimo más")
    void elCargoDelCheckoutRespetaElBordeExacto() {
        sembrarSaldo(clienteId, 4_999L);

        walletUseCase.charge(clienteId, 4_999L, UUID.randomUUID(), "cargo-exacto", "pedido al límite");
        assertThat(saldoEnBd(clienteId)).as("el cargo por el saldo entero deja el monedero a cero").isZero();

        assertThatThrownBy(() -> walletUseCase.charge(clienteId, 1L, UUID.randomUUID(), "cargo-sobrante", "un céntimo"))
                .isInstanceOf(BusinessException.class);
        assertThat(saldoEnBd(clienteId)).as("y el rechazo no deja el saldo en negativo").isZero();
    }

    /**
     * Saldo cero: cualquier cobro, por pequeño que sea, se rechaza. Es el caso que un sistema de pago ve
     * todos los días y el que peor se prueba, porque "no hay dinero" parece obvio hasta que no lo es.
     */
    @Test
    @DisplayName("con el saldo a cero se rechaza hasta un cobro de un céntimo")
    void conSaldoCeroSeRechazaCualquierCobro() {
        sembrarSaldo(clienteId, 0L);

        assertThatThrownBy(() -> walletUseCase.charge(clienteId, 1L, UUID.randomUUID(), "sin-fondo", "un céntimo"))
                .isInstanceOf(BusinessException.class);
        assertThat(saldoEnBd(clienteId)).isZero();
        assertThat(numeroApuntes(clienteId)).as("sin cobro no hay apunte").isZero();
    }

    /**
     * Importe cero y negativo en el cargo: se rechazan antes de tocar el saldo. Un cargo negativo, si
     * colara, sería un ABONO disfrazado de cobro (dinero gratis por la puerta de atrás).
     */
    @Test
    @DisplayName("un cargo de importe cero o negativo se rechaza sin tocar el saldo")
    void cargoDeImporteCeroONegativoSeRechaza() {
        sembrarSaldo(clienteId, 1_000L);

        assertThatThrownBy(() -> walletUseCase.charge(clienteId, 0L, UUID.randomUUID(), "cargo-cero", "cero"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> walletUseCase.charge(clienteId, -500L, UUID.randomUUID(), "cargo-negativo", "negativo"))
                .isInstanceOf(BusinessException.class);

        assertThat(saldoEnBd(clienteId)).as("ni el cero ni el negativo mueven el saldo").isEqualTo(1_000L);
        assertThat(numeroApuntes(clienteId)).isZero();
    }

    /** Cobrar a quien no tiene monedero es un 404, no un monedero creado al vuelo con saldo negativo. */
    @Test
    @DisplayName("cobrar sobre un monedero que no existe es un no encontrado")
    void cobrarSobreMonederoInexistenteEsNoEncontrado() {
        assertThatThrownBy(() -> walletUseCase.charge(clienteId, 100L, UUID.randomUUID(), "sin-wallet", "sin monedero"))
                .isInstanceOf(NotFoundException.class);
    }

    /**
     * El saldo RETENIDO no es gastable: el borde se mide sobre el disponible (saldo − retenido) y no
     * sobre el saldo bruto. Si el cargo mirase el bruto, un pedido podría comerse dinero ya comprometido
     * en otro.
     */
    @Test
    @DisplayName("el cobro se mide contra el disponible: lo retenido no se puede gastar")
    void elCobroSeMideContraElDisponible() {
        sembrarSaldo(clienteId, 10_000L);
        jdbcTemplate.update("UPDATE wallet SET hold_usd_cents = 4000 WHERE user_id = ?", clienteId);

        Map<String, Object> monedero = json(HttpMethod.GET, ME_WALLET, clienteToken, null, 200);
        assertThat(numero(monedero, "availableUsdCents")).as("disponible = 10000 − 4000").isEqualTo(6_000L);

        assertThatThrownBy(() -> walletUseCase.charge(clienteId, 6_001L, UUID.randomUUID(), "sobre-hold", "de más"))
                .isInstanceOf(BusinessException.class);
        assertThat(saldoEnBd(clienteId)).as("el rechazo no toca el saldo").isEqualTo(10_000L);

        walletUseCase.charge(clienteId, 6_000L, UUID.randomUUID(), "justo-hold", "el disponible entero");
        assertThat(saldoEnBd(clienteId)).as("cobrado el disponible exacto queda solo lo retenido").isEqualTo(4_000L);
    }

    /* ============================== Concurrencia ============================== */

    /**
     * El patrón que destapó el doble gasto, ahora por HTTP y sobre el ajuste del administrador: dos
     * retiradas SIMULTÁNEAS por el saldo completo. Solo puede prosperar una, el saldo tiene que quedar
     * exactamente a cero y el libro mayor con UN solo apunte. Con claves de idempotencia DISTINTAS, para
     * que lo único que pueda frenar a la segunda sea la condición atómica del saldo y no la idempotencia.
     */
    @Test
    @DisplayName("dos retiradas simultáneas por el saldo completo: solo prospera una y el saldo queda a cero")
    void dosRetiradasSimultaneasPorElSaldoCompletoSoloProsperaUna() throws Exception {
        sembrarSaldo(clienteId, 5_000L);

        List<Callable<Integer>> intentos = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final String clave = "retirada-simultanea-" + i;
            intentos.add(() -> peticion(HttpMethod.POST, String.format(ADMIN_ADJUST, clienteId), adminToken,
                    Map.of(CAMPO_IMPORTE, -5_000L, CAMPO_DESCRIPCION, "retirada total", CLAVE_IDEM, clave), null)
                    .expectBody().returnResult().getStatus().value());
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> resultados = pool.invokeAll(intentos);
        int prosperaron = 0;
        for (Future<Integer> resultado : resultados) {
            if (resultado.get() == 200) {
                prosperaron++;
            }
        }
        pool.shutdown();

        assertThat(prosperaron).as("con 5000 de saldo y dos retiradas de 5000 solo cabe una").isEqualTo(1);
        assertThat(saldoEnBd(clienteId)).as("ni saldo negativo ni dinero fantasma").isZero();
        assertThat(numeroApuntes(clienteId)).as("un solo apunte en el libro mayor").isEqualTo(1L);
    }

    /* ============================== Recargas ============================== */

    /**
     * Protección viva: una recarga REAL (con sesión de la pasarela, {@code cs_test_…}) NO se puede
     * acreditar con la confirmación simulada. Sin ella, cualquiera inicia una recarga y se la "confirma"
     * gratis: dinero libre en producción. La referencia de la pasarela se fuerza en la base porque en un
     * entorno de test la pasarela está apagada y todo nace ya como simulado.
     */
    @Test
    @DisplayName("una recarga real no se puede acreditar con la confirmación simulada")
    void laRecargaRealNoSeAcreditaConLaConfirmacionSimulada() {
        UUID pagoId = idDePago(iniciarRecarga(clienteToken, 10_000L));
        jdbcTemplate.update("UPDATE payment SET provider_ref = ? WHERE id = ?", "cs_test_sesion_real", pagoId);

        long antes = saldoPorHttp(clienteToken);
        Map<String, Object> error = json(HttpMethod.POST, String.format(ME_CONFIRM_MOCK, pagoId), clienteToken, null,
                NO_PROCESABLE);

        assertThat(String.valueOf(error.get("code"))).isEqualTo("PAYMENT_REQUIRES_REAL_CONFIRMATION");
        assertThat(saldoPorHttp(clienteToken)).as("el saldo no se mueve").isEqualTo(antes);
        assertThat(saldoEnBd(clienteId)).isZero();
    }

    /**
     * Reenviar la MISMA confirmación no abona dos veces. El abono lleva clave de idempotencia
     * ({@code deposit-<pagoId>}), así que el segundo intento devuelve el mismo estado y deja el saldo
     * intacto. Se prueban las dos vías de confirmación, porque las dos acreditan.
     */
    @Test
    @DisplayName("reenviar la misma confirmación de recarga no abona dos veces")
    void reenviarLaConfirmacionNoAbonaDosVeces() {
        UUID pagoId = idDePago(iniciarRecarga(clienteToken, 3_300L));

        json(HttpMethod.POST, String.format(ME_CONFIRM_MOCK, pagoId), clienteToken, null, 200);
        json(HttpMethod.POST, String.format(ME_CONFIRM_MOCK, pagoId), clienteToken, null, 200);
        json(HttpMethod.POST, String.format(ME_CONFIRM_REAL, pagoId), clienteToken, null, 200);

        assertThat(saldoPorHttp(clienteToken)).as("abonado UNA vez: 3.300 céntimos").isEqualTo(3_300L);
        assertThat(numeroApuntes(clienteId)).as("un solo apunte de abono").isEqualTo(1L);
    }

    /** La misma clave de idempotencia en el INICIO de la recarga devuelve el mismo pago, no dos. */
    @Test
    @DisplayName("iniciar dos veces la recarga con la misma clave de idempotencia devuelve el mismo pago")
    void laMismaClaveDeIdempotenciaNoIniciaDosRecargas() {
        Map<String, Object> cuerpo = Map.of("method", METODO_TARJETA, DIVISA_MOSTRADA, "USD", IMPORTE_MOSTRADO,
                new BigDecimal("40.00"));

        Map<String, Object> primera = json(HttpMethod.POST, ME_RECHARGE, clienteToken, cuerpo, 200, "recarga-unica");
        Map<String, Object> segunda = json(HttpMethod.POST, ME_RECHARGE, clienteToken, cuerpo, 200, "recarga-unica");

        assertThat(primera.get(CAMPO_ID_PAGO)).as("el reintento devuelve EL MISMO pago")
                .isEqualTo(segunda.get(CAMPO_ID_PAGO));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM payment WHERE user_id = ?", Long.class, clienteId))
                .as("un solo pago en la base").isEqualTo(1L);

        json(HttpMethod.POST, String.format(ME_CONFIRM_MOCK, idDePago(primera)), clienteToken, null, 200);
        assertThat(saldoPorHttp(clienteToken)).as("40,00 USD = 4.000 céntimos, una sola vez").isEqualTo(4_000L);
    }

    /**
     * Redondeo y divisas: el importe canónico en USD lo calcula el backend a partir de lo que el cliente
     * teclea en SU divisa, y la liquidación sigue la regla EUR→EUR, USD→USD, resto→USD. Se eligen a
     * propósito importes que NO convierten exacto, que es donde se pierden o se ganan céntimos. El
     * importe liquidado se lee de la columna del pago, que es lo que se le cobra de verdad al cliente.
     */
    @Test
    @DisplayName("la recarga en divisa acredita el USD exacto y liquida en la moneda que toca, sin perder céntimos")
    void laRecargaEnDivisaNoPierdeNiGanaCentimos() {
        // Tasas fijadas por el propio servicio (persiste y refresca su caché): 1 USD = 0,90 € y 17,00 MXN.
        currencyRateService.applyBulkSync(Map.of("EUR", new BigDecimal("0.90"), "MXN", new BigDecimal("17.00")));

        // 10,00 € / 0,90 = 11,1111 USD → 1.111 céntimos (HALF_UP). Se cobran 10,00 € EXACTOS en EUR.
        Map<String, Object> enEuros = json(HttpMethod.POST, ME_RECHARGE, clienteToken, Map.of("method", METODO_TARJETA,
                DIVISA_MOSTRADA, "EUR", IMPORTE_MOSTRADO, new BigDecimal("10.00")), 200);
        assertThat(numero(enEuros, CAMPO_IMPORTE_USD)).isEqualTo(1_111L);
        assertThat(String.valueOf(enEuros.get("chargeCurrency"))).as("Stripe en EUR liquida en EUR").isEqualTo("EUR");
        assertThat(importeLiquidado(idDePago(enEuros))).as("se cobran los 10,00 € tecleados, ni un céntimo más")
                .isEqualByComparingTo("10.00");

        // 100,00 MXN / 17,00 = 5,8824 USD → 588 céntimos. Se muestra en MXN pero se liquida en USD.
        Map<String, Object> enPesos = json(HttpMethod.POST, ME_RECHARGE, clienteToken, Map.of("method", METODO_TARJETA,
                DIVISA_MOSTRADA, "MXN", IMPORTE_MOSTRADO, new BigDecimal("100.00")), 200);
        assertThat(numero(enPesos, CAMPO_IMPORTE_USD)).isEqualTo(588L);
        assertThat(String.valueOf(enPesos.get("chargeCurrency"))).as("cualquier otra divisa liquida en USD")
                .isEqualTo("USD");
        assertThat(importeLiquidado(idDePago(enPesos))).isEqualByComparingTo("5.88");

        json(HttpMethod.POST, String.format(ME_CONFIRM_MOCK, idDePago(enEuros)), clienteToken, null, 200);
        json(HttpMethod.POST, String.format(ME_CONFIRM_MOCK, idDePago(enPesos)), clienteToken, null, 200);
        assertThat(saldoPorHttp(clienteToken)).as("1111 + 588, sin arrastrar redondeos").isEqualTo(1_699L);
    }

    /** Por debajo del mínimo de 1,00 USD la recarga se rechaza: no se abre un pago que no se puede cobrar. */
    @Test
    @DisplayName("una recarga por debajo del mínimo de un dólar se rechaza")
    void recargaPorDebajoDelMinimoSeRechaza() {
        peticion(HttpMethod.POST, ME_RECHARGE, clienteToken, Map.of("method", METODO_TARJETA, DIVISA_MOSTRADA, "USD",
                IMPORTE_MOSTRADO, new BigDecimal("0.99")), null).expectStatus().isEqualTo(NO_PROCESABLE);

        assertThat(saldoEnBd(clienteId)).isZero();
    }

    /** Y por encima del tope defensivo tampoco: el importe se valida antes de llegar a la pasarela. */
    @Test
    @DisplayName("una recarga por encima del tope se rechaza")
    void recargaPorEncimaDelTopeSeRechaza() {
        peticion(HttpMethod.POST, ME_RECHARGE, clienteToken, Map.of("method", METODO_TARJETA, DIVISA_MOSTRADA, "USD",
                IMPORTE_MOSTRADO, new BigDecimal("2000000")), null).expectStatus().isBadRequest();

        assertThat(saldoEnBd(clienteId)).isZero();
    }

    /* ============================== Ajustes de administración ============================== */

    /**
     * El ajuste manual (positivo y negativo) queda REGISTRADO y ETIQUETADO: apunte de tipo
     * {@code ADJUSTMENT} y descripción con el prefijo {@code [Adjustment]} y el motivo que escribe quien
     * lo hace. Un movimiento de dinero sin rastro de quién y por qué no es auditable.
     */
    @Test
    @DisplayName("el ajuste manual del admin, positivo y negativo, queda registrado y etiquetado")
    void elAjusteManualQuedaRegistradoYEtiquetado() {
        sembrarSaldo(clienteId, 2_000L);

        Map<String, Object> aFavor = ajusteAdmin(clienteId, 1_234L, "compensación por incidencia", "aj-1");
        Map<String, Object> enContra = ajusteAdmin(clienteId, -234L, "cargo por devolución", "aj-2");

        assertThat(numero(aFavor, CAMPO_SALDO_POSTERIOR)).as("2000 + 1234").isEqualTo(3_234L);
        assertThat(numero(enContra, CAMPO_SALDO_POSTERIOR)).as("3234 − 234").isEqualTo(3_000L);
        assertThat(saldoPorHttp(clienteToken)).isEqualTo(3_000L);

        List<Map<String, Object>> apuntes = movimientosAntiguoPrimero(clienteToken);
        assertThat(apuntes).hasSize(2);
        assertThat(apuntes).allSatisfy(apunte -> {
            assertThat(String.valueOf(apunte.get("kind"))).isEqualTo("ADJUSTMENT");
            assertThat(String.valueOf(apunte.get(CAMPO_DESCRIPCION))).startsWith("[Adjustment] ");
        });
        assertThat(String.valueOf(apuntes.get(0).get(CAMPO_DESCRIPCION))).contains("compensación por incidencia");
        assertThat(String.valueOf(apuntes.get(1).get(CAMPO_DESCRIPCION))).contains("cargo por devolución");
    }

    /** Un ajuste de importe cero no es un ajuste: se rechaza en vez de dejar un apunte vacío en el libro. */
    @Test
    @DisplayName("un ajuste manual de importe cero se rechaza")
    void ajusteDeImporteCeroSeRechaza() {
        sembrarSaldo(clienteId, 1_000L);

        peticion(HttpMethod.POST, String.format(ADMIN_ADJUST, clienteId), adminToken,
                Map.of(CAMPO_IMPORTE, 0L, CAMPO_DESCRIPCION, "nada", CLAVE_IDEM, "aj-cero"), null)
                .expectStatus().isEqualTo(NO_PROCESABLE);

        assertThat(saldoEnBd(clienteId)).isEqualTo(1_000L);
        assertThat(numeroApuntes(clienteId)).isZero();
    }

    /** Sin motivo escrito no hay ajuste: el libro mayor no admite movimientos anónimos. */
    @Test
    @DisplayName("un ajuste manual sin descripción se rechaza")
    void ajusteSinDescripcionSeRechaza() {
        sembrarSaldo(clienteId, 1_000L);

        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put(CAMPO_IMPORTE, 500L);
        cuerpo.put(CAMPO_DESCRIPCION, "   ");
        peticion(HttpMethod.POST, String.format(ADMIN_ADJUST, clienteId), adminToken, cuerpo, null)
                .expectStatus().isBadRequest();

        assertThat(saldoEnBd(clienteId)).isEqualTo(1_000L);
        assertThat(numeroApuntes(clienteId)).isZero();
    }

    /** El alta manual es un ABONO: un importe no positivo lo corta la validación del contrato. */
    @Test
    @DisplayName("un alta manual de importe cero o negativo se rechaza")
    void altaManualDeImporteNoPositivoSeRechaza() {
        peticion(HttpMethod.POST, String.format(ADMIN_TOPUP, clienteId), adminToken,
                Map.of(CAMPO_IMPORTE, 0L, CAMPO_DESCRIPCION, "cero"), null).expectStatus().isBadRequest();
        peticion(HttpMethod.POST, String.format(ADMIN_TOPUP, clienteId), adminToken,
                Map.of(CAMPO_IMPORTE, -100L, CAMPO_DESCRIPCION, "negativo"), null).expectStatus().isBadRequest();

        assertThat(saldoEnBd(clienteId)).isZero();
    }

    /** Reenviar el MISMO alta manual (misma clave) no abona dos veces: es el webhook duplicado de siempre. */
    @Test
    @DisplayName("repetir el alta manual con la misma clave de idempotencia no abona dos veces")
    void altaManualRepetidaConLaMismaClaveNoAbonaDosVeces() {
        sembrarSaldo(clienteId, 0L);

        Map<String, Object> primera = abonoAdmin(clienteId, 6_000L, "regalo", "abono-unico");
        Map<String, Object> segunda = abonoAdmin(clienteId, 6_000L, "regalo", "abono-unico");

        assertThat(segunda.get("transactionId")).as("el reintento devuelve EL MISMO apunte")
                .isEqualTo(primera.get("transactionId"));
        assertThat(saldoPorHttp(clienteToken)).as("abonado una sola vez").isEqualTo(6_000L);
        assertThat(numeroApuntes(clienteId)).isEqualTo(1L);
    }

    /** La vista de administración devuelve el saldo en dólares con dos decimales, sin perder el céntimo. */
    @Test
    @DisplayName("el detalle de admin muestra el saldo exacto en dólares")
    void elDetalleDeAdminMuestraElSaldoExacto() {
        sembrarSaldo(clienteId, 12_345L);
        jdbcTemplate.update("UPDATE wallet SET hold_usd_cents = 45 WHERE user_id = ?", clienteId);

        Map<String, Object> detalle = json(HttpMethod.GET, String.format(ADMIN_DETAIL, clienteId), adminToken, null,
                200);

        assertThat(decimal(detalle, "balanceUsd")).isEqualByComparingTo("123.45");
        assertThat(decimal(detalle, "holdUsd")).isEqualByComparingTo("0.45");
        assertThat(decimal(detalle, "availableUsd")).as("123,45 − 0,45").isEqualByComparingTo("123.00");
    }

    /* ============================== Devoluciones ============================== */

    /**
     * Ida y vuelta EXACTA: lo devuelto es exactamente lo cobrado y el monedero vuelve al saldo de
     * partida. Se ataca el método real del caso de uso porque el reembolso solo se alcanza desde el
     * pedido; lo que se certifica aquí es el importe, no el flujo del pedido.
     */
    @Test
    @DisplayName("el reembolso devuelve exactamente lo cobrado y deja el saldo como estaba")
    void elReembolsoDevuelveExactamenteLoCobrado() {
        sembrarSaldo(clienteId, 9_000L);
        UUID pedidoId = UUID.randomUUID();

        walletUseCase.charge(clienteId, 3_456L, pedidoId, "cargo-" + pedidoId, "pedido de prueba");
        assertThat(saldoEnBd(clienteId)).isEqualTo(5_544L);

        walletUseCase.refund(clienteId, 3_456L, pedidoId, "reembolso-" + pedidoId, "devolución");
        assertThat(saldoEnBd(clienteId)).as("9000 − 3456 + 3456").isEqualTo(9_000L);

        List<Map<String, Object>> apuntes = movimientosAntiguoPrimero(clienteToken);
        assertThat(apuntes).hasSize(2);
        assertThat(numero(apuntes.get(0), CAMPO_IMPORTE_USD)).as("el cobro va con signo negativo").isEqualTo(-3_456L);
        assertThat(numero(apuntes.get(1), CAMPO_IMPORTE_USD)).as("y la devolución con signo positivo")
                .isEqualTo(3_456L);
        assertThat(numero(apuntes.get(1), "balanceAfterCents")).isEqualTo(9_000L);
    }

    /** Reenviar el MISMO reembolso (misma clave) no devuelve el dinero dos veces. */
    @Test
    @DisplayName("repetir el mismo reembolso con la misma clave no devuelve el dinero dos veces")
    void reembolsoRepetidoNoDevuelveDosVeces() {
        sembrarSaldo(clienteId, 9_000L);
        UUID pedidoId = UUID.randomUUID();
        walletUseCase.charge(clienteId, 3_456L, pedidoId, "cargo-" + pedidoId, "pedido de prueba");

        walletUseCase.refund(clienteId, 3_456L, pedidoId, "reembolso-unico", "devolución");
        walletUseCase.refund(clienteId, 3_456L, pedidoId, "reembolso-unico", "devolución repetida");

        assertThat(saldoEnBd(clienteId)).as("devuelto una sola vez").isEqualTo(9_000L);
        assertThat(numeroApuntes(clienteId)).as("un cobro y una devolución, nada más").isEqualTo(2L);
    }

    /* ============================== Autorización cruzada ============================== */

    /**
     * La wallet de otro no se toca: ni se lee, ni se recarga, ni se confirma su pago. El pago ajeno se
     * responde como inexistente (404) y no como prohibido, porque un 403 confirmaría que ese pago existe.
     */
    @Test
    @DisplayName("un usuario no puede operar la wallet de otro")
    void unUsuarioNoPuedeOperarLaWalletDeOtro() {
        sembrarSaldo(clienteId, 5_000L);
        sembrarSaldo(intrusoId, 100L);

        // Las palancas de dinero del panel son exclusivas del administrador.
        peticion(HttpMethod.POST, String.format(ADMIN_TOPUP, clienteId), intrusoToken,
                Map.of(CAMPO_IMPORTE, 100_000L, CAMPO_DESCRIPCION, "me regalo saldo"), null)
                .expectStatus().isForbidden();
        peticion(HttpMethod.POST, String.format(ADMIN_ADJUST, clienteId), intrusoToken,
                Map.of(CAMPO_IMPORTE, -5_000L, CAMPO_DESCRIPCION, "te vacío"), null).expectStatus().isForbidden();
        peticion(HttpMethod.GET, String.format(ADMIN_DETAIL, clienteId), intrusoToken, null, null)
                .expectStatus().isForbidden();

        // Cada uno ve SU saldo, nunca el del otro.
        assertThat(saldoPorHttp(clienteToken)).isEqualTo(5_000L);
        assertThat(saldoPorHttp(intrusoToken)).isEqualTo(100L);

        // Y el pago de otro no se confirma, ni por la vía simulada ni por la normal.
        UUID pagoAjeno = idDePago(iniciarRecarga(clienteToken, 20_000L));
        json(HttpMethod.POST, String.format(ME_CONFIRM_MOCK, pagoAjeno), intrusoToken, null, 404);
        json(HttpMethod.POST, String.format(ME_CONFIRM_REAL, pagoAjeno), intrusoToken, null, 404);

        assertThat(saldoEnBd(clienteId)).as("el dueño del pago no recibe el abono que otro intentó confirmar")
                .isEqualTo(5_000L);
        assertThat(saldoEnBd(intrusoId)).as("y el intruso tampoco").isEqualTo(100L);
    }

    /** Sin token no hay monedero: el saldo no es información pública. */
    @Test
    @DisplayName("sin autenticar no se puede consultar ninguna wallet")
    void sinAutenticarNoSeConsultaLaWallet() {
        client.get().uri(ME_WALLET).exchange().expectStatus().isUnauthorized();
        client.get().uri(ME_WALLET_TX).exchange().expectStatus().isUnauthorized();
        client.get().uri(String.format(ADMIN_DETAIL, clienteId)).exchange().expectStatus().isUnauthorized();
    }

    /**
     * Consultar el extracto no puede CREAR el monedero. Cuando lo hacía, el buscador del panel enseñaba
     * monederos fantasma que no existían en la base.
     */
    @Test
    @DisplayName("el extracto de quien no tiene wallet va vacío y no la crea")
    void elExtractoDeQuienNoTieneWalletNoLaCrea() {
        Map<String, Object> pagina = json(HttpMethod.GET, ME_WALLET_TX, clienteToken, null, 200);

        assertThat((List<?>) pagina.get("items")).isEmpty();
        assertThat(numero(pagina, "totalElements")).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM wallet WHERE user_id = ?", Long.class, clienteId))
                .as("consultar el extracto no abre monedero").isZero();
    }

    /* ============================== Utilidades ============================== */

    /** Usuario mínimo en la tabla real: lo que se certifica es el dinero, no el alta de la cuenta. */
    private UUID crearUsuario(String email, String rol) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, role, active, created_at, updated_at)"
                + " VALUES (?, ?, ?, true, now(), now())", id, email, rol);
        return id;
    }

    /**
     * Saldo de partida escrito directamente. Es un ALTA-O-ACTUALIZACIÓN a propósito: varios movimientos
     * (la recarga, el alta manual) abren el monedero por su cuenta, y sembrar después no puede duplicar
     * la fila ni reventar por la clave única.
     */
    private void sembrarSaldo(UUID userId, long centimos) {
        int actualizadas = jdbcTemplate.update("UPDATE wallet SET balance_usd_cents = ?, updated_at = now()"
                + " WHERE user_id = ?", centimos, userId);
        if (actualizadas == 0) {
            jdbcTemplate.update("INSERT INTO wallet (id, user_id, balance_usd_cents, hold_usd_cents, currency_default,"
                    + " status, created_at, updated_at) VALUES (?, ?, ?, 0, 'USD', 'ACTIVE', now(), now())",
                    UUID.randomUUID(), userId, centimos);
        }
    }

    private long saldoEnBd(UUID userId) {
        Long saldo = jdbcTemplate.queryForObject("SELECT coalesce(max(balance_usd_cents), 0) FROM wallet"
                + " WHERE user_id = ?", Long.class, userId);
        return saldo == null ? 0L : saldo;
    }

    private long numeroApuntes(UUID userId) {
        Long total = jdbcTemplate.queryForObject("SELECT count(*) FROM wallet_transaction t"
                + " JOIN wallet w ON w.id = t.wallet_id WHERE w.user_id = ?", Long.class, userId);
        return total == null ? 0L : total;
    }

    /** Importe REALMENTE cobrado al cliente, tal y como queda escrito en el pago. */
    private BigDecimal importeLiquidado(UUID pagoId) {
        return jdbcTemplate.queryForObject("SELECT settlement_amount FROM payment WHERE id = ?", BigDecimal.class,
                pagoId);
    }

    private long saldoPorHttp(String token) {
        return numero(json(HttpMethod.GET, ME_WALLET, token, null, 200), "balanceUsdCents");
    }

    /**
     * Movimientos del extracto de ANTIGUO a RECIENTE. El API los devuelve al revés (la consulta ordena
     * por fecha descendente), así que basta con darle la vuelta a la página: reordenar por la fecha
     * serializada añadiría una dependencia del formato de fecha que no aporta nada.
     */
    private List<Map<String, Object>> movimientosAntiguoPrimero(String token) {
        Map<String, Object> pagina = json(HttpMethod.GET, ME_WALLET_TX, token, null, 200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) pagina.get("items");
        List<Map<String, Object>> ordenados = new ArrayList<>(items);
        Collections.reverse(ordenados);
        return ordenados;
    }

    private Map<String, Object> abonoAdmin(UUID userId, long centimos, String motivo, String clave) {
        return json(HttpMethod.POST, String.format(ADMIN_TOPUP, userId), adminToken,
                Map.of(CAMPO_IMPORTE, centimos, CAMPO_DESCRIPCION, motivo, CLAVE_IDEM, clave), 200);
    }

    private Map<String, Object> ajusteAdmin(UUID userId, long centimos, String motivo, String clave) {
        return json(HttpMethod.POST, String.format(ADMIN_ADJUST, userId), adminToken,
                Map.of(CAMPO_IMPORTE, centimos, CAMPO_DESCRIPCION, motivo, CLAVE_IDEM, clave), 200);
    }

    /** Inicia una recarga en USD por el importe canónico indicado (en céntimos). */
    private Map<String, Object> iniciarRecarga(String token, long centimosUsd) {
        return json(HttpMethod.POST, ME_RECHARGE, token, Map.of("method", METODO_TARJETA, DIVISA_MOSTRADA, "USD",
                IMPORTE_MOSTRADO, BigDecimal.valueOf(centimosUsd).movePointLeft(2)), 200);
    }

    /** Recarga + confirmación simulada: la única vía de acreditar saldo con la pasarela apagada. */
    private void recargarYConfirmar(String token, long centimosUsd) {
        UUID pagoId = idDePago(iniciarRecarga(token, centimosUsd));
        json(HttpMethod.POST, String.format(ME_CONFIRM_MOCK, pagoId), token, null, 200);
    }

    private UUID idDePago(Map<String, Object> recarga) {
        return UUID.fromString(String.valueOf(recarga.get(CAMPO_ID_PAGO)));
    }

    private long numero(Map<String, Object> cuerpo, String campo) {
        Object valor = cuerpo.get(campo);
        assertThat(valor).as("el campo %s tiene que venir en la respuesta", campo).isNotNull();
        return ((Number) valor).longValue();
    }

    private BigDecimal decimal(Map<String, Object> cuerpo, String campo) {
        return new BigDecimal(String.valueOf(cuerpo.get(campo)));
    }

    private Map<String, Object> json(HttpMethod metodo, String uri, String token, Object cuerpo, int estadoEsperado) {
        return json(metodo, uri, token, cuerpo, estadoEsperado, null);
    }

    private Map<String, Object> json(HttpMethod metodo, String uri, String token, Object cuerpo, int estadoEsperado,
            String claveIdempotencia) {
        return peticion(metodo, uri, token, cuerpo, claveIdempotencia).expectStatus().isEqualTo(estadoEsperado)
                .expectBody(JSON).returnResult().getResponseBody();
    }

    private WebTestClient.ResponseSpec peticion(HttpMethod metodo, String uri, String token, Object cuerpo,
            String claveIdempotencia) {
        WebTestClient.RequestBodySpec spec = client.method(metodo).uri(uri).contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, bearer(token));
        }
        if (claveIdempotencia != null) {
            spec = spec.header("Idempotency-Key", claveIdempotencia);
        }
        return cuerpo == null ? spec.exchange() : spec.bodyValue(cuerpo).exchange();
    }
}
