package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.service.CheckoutPreviewService;
import com.nexaplatform.dropshipping.application.service.CheckoutTotalsService;
import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.application.usecase.PaymentUseCase;
import com.nexaplatform.dropshipping.config.BaseIntegration;
import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.domain.model.Payment;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cambio de moneda e importes: conversión, formateo y coherencia extremo a extremo.
 *
 * <p><b>Por qué existe.</b> La norma del proyecto es que TODO el cálculo y el formateo de precios se
 * hace en el backend y el frontend solo pinta {@code displayFormatted}. Eso concentra el riesgo aquí:
 * si la conversión se desvía un céntimo, se desvía en el escaparate, en el carrito, en el resumen del
 * checkout, en el cobro y en la factura a la vez, y nadie lo ve hasta que un cliente reclama. Un
 * arancel cobrado de más sobrevivió semanas con la suite en verde porque los tests comprobaban «que
 * respondiera 200», no CUÁNTO. Por eso aquí cada importe se compara EXACTO, calculado a mano y al
 * céntimo, contra tasas sembradas por el propio test.
 *
 * <p><b>Cómo se hace predecible.</b> {@link BaseIntegration} vacía todas las tablas antes de cada
 * test, así que no queda ni una tasa ni una regla de margen de las semillas de Liquibase. El
 * {@code @BeforeEach} de esta clase siembra tasas «de laboratorio» (redondas a propósito: CNY=8,
 * EUR=0,92, JPY=150…) y una única regla GLOBAL del 100 %, de forma que todos los importes esperados
 * salen de una multiplicación que se puede hacer de cabeza. Las tasas NO son las reales del mercado y
 * no pretenden serlo: lo que se verifica es la aritmética, no la cotización.
 *
 * <p><b>Cachés.</b> Hay tres cachés en juego y las tres sobreviven al TRUNCATE porque viven en
 * memoria: la de {@link CurrencyRateService} (TTL 5 min), la de {@link MarginService} (TTL 5 min) y
 * las de Spring Cache (Caffeine). Se refrescan/invalidan en el {@code @BeforeEach}; sin eso los tests
 * leerían precios de la semilla de Liquibase y darían verde con números inventados.
 */
@DisplayName("Cambio de moneda: conversión exacta, formateo y coherencia de importes")
class CurrencyConversionIT extends BaseIntegration {

    /* ============================ Tasas de laboratorio (vs USD) ============================ */

    /** 1 USD = 0,92 EUR. Elegida redonda para que 20 USD → 18,40 € salga sin decimales sueltos. */
    private static final String TASA_EUR = "0.92000000";
    /** 1 USD = 150 JPY. El yen NO tiene céntimos: sirve para el caso de divisa sin decimales. */
    private static final String TASA_JPY = "150.00000000";
    /** 1 USD = 8 CNY. Es la moneda de compra del proveedor: divide exacto y el coste sale entero. */
    private static final String TASA_CNY = "8.00000000";
    /** 1 USD = 10 SEK. La corona sueca pinta el símbolo DETRÁS del número ("200,00 kr"). */
    private static final String TASA_SEK = "10.00000000";
    /** 1 USD = 3 MXN. Dividir por 3 produce decimales infinitos: es el caso de redondeo periódico. */
    private static final String TASA_MXN = "3.00000000";
    /** 1 USD = 0,33333333 PLN. Tasa con periodo en la otra dirección (multiplicar, no dividir). */
    private static final String TASA_PLN = "0.33333333";
    /** 1 USD = 0,80 GBP, pero la moneda se siembra DESACTIVADA (active = false). */
    private static final String TASA_GBP = "0.80000000";

    /**
     * Espacio duro (U+00A0). {@code NumberFormat} separa el número del símbolo con NBSP en es-ES,
     * sv-SE y pl-PL. Se escribe explícito en los literales esperados para que la comparación sea
     * EXACTA carácter a carácter y no «casi igual» con un espacio normal.
     */
    private static final String NBSP = " ";

    /** Símbolo del yen en ja-JP: es el YEN de ancho completo (U+FFE5), no el ¥ latino (U+00A5). */
    private static final String YEN_ANCHO = "￥";

    private static final String CABECERA_DIVISA = "X-Currency";
    private static final String DETALLE = "/api/catalog/products/by-id/{id}";
    private static final String LISTADO = "/api/catalog/products";
    private static final String CARRITO = "/api/catalog/cart-quote";
    private static final String CHECKOUT = "/api/shipping/quote";

    /**
     * ObjectMapper propio con {@code USE_BIG_DECIMAL_FOR_FLOATS}: sin él Jackson convierte los números
     * del JSON a {@code double} y se pierde la escala (18.40 llegaría como 18.4), justo el detalle que
     * este test tiene que comprobar. Leyendo el cuerpo como texto y parseándolo aquí, el importe del
     * cable se compara tal cual viaja.
     */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    @Autowired
    private CurrencyRateService currencyRateService;

    @Autowired
    private MarginService marginService;

    @Autowired
    private CheckoutTotalsService checkoutTotalsService;

    @Autowired
    private CheckoutPreviewService checkoutPreviewService;

    @Autowired
    private PaymentUseCase paymentUseCase;

    @Autowired
    private CacheManager cacheManager;

    /** Producto de referencia: 80 CNY de coste → 10 USD → margen 100 % → 20,00 USD de venta. */
    private Producto referencia;

    /** Identificadores de un producto sembrado y de su única variante activa. */
    private record Producto(UUID id, UUID variantId) {
    }

    @BeforeEach
    void prepararDivisasCatalogoYCaches() {
        vaciarCachesDeAplicacion();
        sembrarDivisas();
        refrescarCacheDeDivisas();
        sembrarMargenGlobal("100.0000");
        referencia = sembrarProducto("producto-referencia", "REF-1", "80.0000", "0.0000", "0.0000");
    }

    @AfterEach
    void limpiarDivisaDelHilo() {
        // Los tests que llaman a los servicios directamente fijan la divisa en el ThreadLocal. Si no se
        // limpia, el siguiente test del mismo hilo hereda la divisa del anterior y «pasa» por accidente.
        CurrencyHolder.clear();
    }

    /* ==================================================================================== */
    /* 1. El mismo producto en distintas divisas                                            */
    /* ==================================================================================== */

    @Test
    @DisplayName("El mismo producto en distintas divisas se convierte EXACTO con la tasa vigente")
    void mismoProductoEnVariasDivisas_conviertaConLaTasaVigente() {
        // 80 CNY / 8 = 10,00 USD de coste; margen global 100 % → 20,00 USD de venta (canónico).
        // El coste y el canónico solo se sirven al admin, de ahí la ficha con ese rol.
        JsonNode enUsd = fichaAdmin("USD");
        assertThat(enUsd.get("retailUsd").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(enUsd.get("costUsd").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(enUsd.get("displayPrice").decimalValue()).isEqualByComparingTo("20.00");

        // A partir de aquí SOLO cambia la tasa: el canónico en dólares no se mueve nunca.
        assertThat(fichaEnDivisa("EUR").get("displayPrice").decimalValue()).isEqualByComparingTo("18.40");
        assertThat(fichaEnDivisa("CNY").get("displayPrice").decimalValue()).isEqualByComparingTo("160.00");
        assertThat(fichaEnDivisa("SEK").get("displayPrice").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(fichaEnDivisa("MXN").get("displayPrice").decimalValue()).isEqualByComparingTo("60.00");
        // 20 × 0,33333333 = 6,6666666 → un ÚNICO redondeo final a 2 decimales.
        assertThat(fichaEnDivisa("PLN").get("displayPrice").decimalValue()).isEqualByComparingTo("6.67");
        // El yen no tiene céntimos: 20 × 150 = 3000 exactos, sin parte decimal.
        assertThat(fichaEnDivisa("JPY").get("displayPrice").decimalValue()).isEqualByComparingTo("3000");

        // El canónico USD es el MISMO en todas: la divisa es presentación, no negocio.
        for (String divisa : List.of("EUR", "CNY", "SEK", "MXN", "PLN", "JPY")) {
            assertThat(fichaAdmin(divisa).get("retailUsd").decimalValue()).as("retailUsd con X-Currency=%s", divisa)
                    .isEqualByComparingTo("20.00");
        }
    }

    @Test
    @DisplayName("Cambiar de divisa no sirve un precio cacheado de la divisa anterior")
    void cambiarDeDivisa_noSirvePrecioCacheadoDeOtraDivisa() {
        // La ficha se cachea (Caffeine). Si la clave no incluyera la divisa, el segundo visitante vería
        // el precio del primero: el euro pintado con el número del dólar.
        assertThat(fichaEnDivisa("USD").get("displayFormatted").asText()).isEqualTo("$20.00");
        assertThat(fichaEnDivisa("EUR").get("displayFormatted").asText()).isEqualTo("18,40" + NBSP + "€");
        assertThat(fichaEnDivisa("USD").get("displayFormatted").asText()).isEqualTo("$20.00");
    }

    /* ==================================================================================== */
    /* 2. displayFormatted coincide con el número                                           */
    /* ==================================================================================== */

    @Test
    @DisplayName("displayFormatted coincide con el número: símbolo, separadores y decimales del locale")
    void displayFormatted_coincideConElNumero() {
        // en-US: símbolo delante, coma de miles, punto decimal.
        assertThat(fichaEnDivisa("USD").get("displayFormatted").asText()).isEqualTo("$20.00");
        // es-ES: símbolo DETRÁS con espacio duro, punto de miles, coma decimal.
        assertThat(fichaEnDivisa("EUR").get("displayFormatted").asText()).isEqualTo("18,40" + NBSP + "€");
        // zh-CN: yuan con símbolo delante y punto decimal.
        assertThat(fichaEnDivisa("CNY").get("displayFormatted").asText()).isEqualTo("¥160.00");
        // sv-SE: "kr" DETRÁS del número, coma decimal.
        assertThat(fichaEnDivisa("SEK").get("displayFormatted").asText()).isEqualTo("200,00" + NBSP + "kr");
        // pl-PL: "zł" detrás; 6,67 es el único redondeo de 6,6666666.
        assertThat(fichaEnDivisa("PLN").get("displayFormatted").asText()).isEqualTo("6,67" + NBSP + "zł");
        // ja-JP: SIN decimales y con el yen de ancho completo.
        assertThat(fichaEnDivisa("JPY").get("displayFormatted").asText()).isEqualTo(YEN_ANCHO + "3,000");

        // Y el símbolo suelto que expone la API es el de la BD, no uno inventado por el front.
        assertThat(fichaEnDivisa("EUR").get("displaySymbol").asText()).isEqualTo("€");
        assertThat(fichaEnDivisa("SEK").get("displaySymbol").asText()).isEqualTo("kr");
    }

    @Test
    @DisplayName("El texto formateado y el número son el MISMO importe (no se formatea otra cifra)")
    void textoFormateadoYNumero_sonElMismoImporte() {
        // La comprobación que faltaba cuando se coló el arancel: el string y el número podían divergir
        // sin que nadie lo notara, porque cada uno salía de una rama distinta del código.
        for (String divisa : List.of("USD", "EUR", "CNY", "SEK", "MXN", "PLN", "JPY")) {
            JsonNode ficha = fichaEnDivisa(divisa);
            BigDecimal numero = ficha.get("displayPrice").decimalValue();
            String texto = ficha.get("displayFormatted").asText();
            assertThat(texto).as("displayFormatted en %s", divisa)
                    .isEqualTo(currencyRateService.formatDisplay(numero, divisa));
        }
    }

    @Test
    @DisplayName("Los miles se agrupan según el locale también en importes grandes")
    void importesGrandes_agrupanMilesSegunElLocale() {
        // 8.000.000 CNY / 8 = 1.000.000 USD de coste → margen 100 % → 2.000.000 USD de venta.
        Producto caro = sembrarProducto("producto-caro", "CARO-1", "8000000.0000", "0.0000", "0.0000");

        JsonNode enUsd = fichaEnDivisa(caro.id(), "USD");
        assertThat(enUsd.get("displayPrice").decimalValue()).isEqualByComparingTo("2000000.00");
        assertThat(enUsd.get("displayFormatted").asText()).isEqualTo("$2,000,000.00");

        JsonNode enEuros = fichaEnDivisa(caro.id(), "EUR");
        assertThat(enEuros.get("displayPrice").decimalValue()).isEqualByComparingTo("1840000.00");
        assertThat(enEuros.get("displayFormatted").asText()).isEqualTo("1.840.000,00" + NBSP + "€");
    }

    /* ==================================================================================== */
    /* 3. Coherencia extremo a extremo                                                      */
    /* ==================================================================================== */

    @Test
    @DisplayName("Catálogo, ficha, carrito, vista previa del checkout y pedido dan EL MISMO importe")
    void coherenciaExtremoAExtremo_mismoImporteEnTodaLaCadena() {
        String esperadoEur = "18,40" + NBSP + "€";
        // El mismo comprador recorre toda la cadena: el listado exige sesión (ver más abajo) y el
        // resumen del checkout y el pedido tienen que ser suyos para que los importes sean comparables.
        UUID comprador = UUID.randomUUID();

        // (1) Catálogo (listado). Exige producto ACTIVE con imagen espejada, que es lo que siembra el
        // fixture, y AUTENTICACIÓN: GET /api/catalog/products está detrás del muro anti-clonado, porque
        // es el endpoint que permite enumerar el catálogo entero (100 productos por llamada, con precio
        // y métricas de venta). Sin token responde 401, que es lo correcto; el que hacía falta corregir
        // era este test, que lo pedía como anónimo.
        JsonNode listado = json(get(LISTADO, "EUR", comprador));
        JsonNode fila = buscarEnListado(listado, referencia.id());
        assertThat(fila).as("el producto de referencia debe aparecer en el listado").isNotNull();
        assertThat(fila.get("displayPrice").decimalValue()).isEqualByComparingTo("18.40");
        assertThat(fila.get("displayFormatted").asText()).isEqualTo(esperadoEur);

        // (2) Ficha de producto.
        JsonNode ficha = fichaEnDivisa("EUR");
        assertThat(ficha.get("displayPrice").decimalValue()).isEqualByComparingTo("18.40");
        assertThat(ficha.get("displayFormatted").asText()).isEqualTo(esperadoEur);

        // (3) Carrito (re-cotización al precio actual).
        JsonNode carrito = json(cotizarCarrito("EUR", referencia, 1));
        assertThat(carrito.get("currency").asText()).isEqualTo("EUR");
        assertThat(carrito.get("items").get(0).get("unit").decimalValue()).isEqualByComparingTo("18.40");
        assertThat(carrito.get("items").get(0).get("unitFormatted").asText()).isEqualTo(esperadoEur);
        assertThat(carrito.get("subtotal").decimalValue()).isEqualByComparingTo("18.40");
        assertThat(carrito.get("subtotalFormatted").asText()).isEqualTo(esperadoEur);

        // (4) Vista previa del checkout. Sin zonas de envío ni IVA sembrados el destino sale "no
        // soportado": envío 0 e impuesto 0. Es deliberado — así el total del resumen ES el subtotal y
        // cualquier desviación de un céntimo sería del cambio de moneda, no del transporte.
        JsonNode preview = json(previsualizarCheckout("EUR", comprador, referencia, 1));
        assertThat(preview.get("totalFormatted").asText()).isEqualTo(esperadoEur);
        assertThat(preview.get("taxRateBps").asInt()).isZero();

        // (5) Importe CANÓNICO que se cobra: 2.000 céntimos de dólar.
        CheckoutPreviewService.Preview canonico = enDivisa("EUR", () -> checkoutPreviewService.compute("ES", null,
                List.of(new CheckoutPreviewService.Line(referencia.id(), referencia.variantId(), 1)), comprador));
        assertThat(canonico.subtotalUsdCents()).isEqualTo(2000);
        assertThat(canonico.totalDisplay()).isEqualByComparingTo("18.40");

        // (6) Pedido ya cobrado por esos 2.000 céntimos: la ficha del cliente enseña el MISMO importe.
        UUID pedido = sembrarPedidoCobrado(comprador, 2000);
        JsonNode detallePedido = json(get("/api/me/orders/" + pedido, "EUR", comprador));
        assertThat(detallePedido.get("total").decimalValue()).isEqualByComparingTo("18.40");
        assertThat(detallePedido.get("totalFormatted").asText()).isEqualTo(esperadoEur);
        assertThat(detallePedido.get("items").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("18.40");
    }

    @Test
    @DisplayName("La cadena completa también cuadra en yenes, que no tienen céntimos")
    void coherenciaExtremoAExtremo_enYenes() {
        String esperadoJpy = YEN_ANCHO + "3,000";
        UUID comprador = UUID.randomUUID();

        assertThat(fichaEnDivisa("JPY").get("displayFormatted").asText()).isEqualTo(esperadoJpy);

        JsonNode carrito = json(cotizarCarrito("JPY", referencia, 1));
        assertThat(carrito.get("subtotal").decimalValue()).isEqualByComparingTo("3000");
        assertThat(carrito.get("subtotalFormatted").asText()).isEqualTo(esperadoJpy);

        JsonNode preview = json(previsualizarCheckout("JPY", comprador, referencia, 1));
        assertThat(preview.get("totalFormatted").asText()).isEqualTo(esperadoJpy);

        UUID pedido = sembrarPedidoCobrado(comprador, 2000);
        JsonNode detallePedido = json(get("/api/me/orders/" + pedido, "JPY", comprador));
        assertThat(detallePedido.get("total").decimalValue()).isEqualByComparingTo("3000");
        assertThat(detallePedido.get("totalFormatted").asText()).isEqualTo(esperadoJpy);
    }

    @Test
    @DisplayName("El IVA y el envío del producto entran en el precio y el desglose es solo para el admin")
    void ivaYEnvio_entranEnElPrecioYElDesgloseEsDeAdmin() {
        // 80 CNY base + 4 CNY de IVA + 12 CNY de envío. El margen grava el desembolso COMPLETO:
        //   base   80/8 = 10,00 USD × 2 = 20,0000
        //   IVA     4/8 =  0,50 USD × 2 =  1,0000
        //   envío  12/8 =  1,50 USD × 2 =  3,0000
        //   canónico = 20,00 + 1,00 + 3,00 = 24,00 USD  →  24 × 0,92 = 22,08 €
        Producto conExtras = sembrarProducto("producto-con-extras", "EXTRA-1", "80.0000", "4.0000", "12.0000");

        JsonNode paraCliente = fichaEnDivisa(conExtras.id(), "EUR");
        assertThat(paraCliente.get("displayPrice").decimalValue()).isEqualByComparingTo("22.08");
        assertThat(paraCliente.get("displayFormatted").asText()).isEqualTo("22,08" + NBSP + "€");
        assertThat(paraCliente.get("baseFormatted").isNull()).as("el desglose no es para el cliente").isTrue();
        assertThat(paraCliente.get("retailUsd").isNull()).as("el canónico tampoco").isTrue();

        // El admin sí ve el desglose, y base + IVA + envío tiene que sumar el total: 18,40 + 0,92 + 2,76.
        JsonNode paraAdmin = fichaAdmin(conExtras.id(), "EUR");
        assertThat(paraAdmin.get("retailUsd").decimalValue()).isEqualByComparingTo("24.00");
        assertThat(paraAdmin.get("baseFormatted").asText()).isEqualTo("18,40" + NBSP + "€");
        assertThat(paraAdmin.get("ivaFormatted").asText()).isEqualTo("0,92" + NBSP + "€");
        assertThat(paraAdmin.get("shippingFormatted").asText()).isEqualTo("2,76" + NBSP + "€");
    }

    /* ==================================================================================== */
    /* 4. Wallet: recarga en divisa activa y liquidación                                    */
    /* ==================================================================================== */

    @Test
    @DisplayName("Recarga con tarjeta en euros: se cobra EXACTAMENTE lo tecleado (EUR→EUR)")
    void recargaConTarjetaEnEuros_liquidaEnEurosSinPerderCentimos() {
        UUID usuario = sembrarUsuario("recarga-eur@nx036.local");

        Payment pago = paymentUseCase.initiateRecharge(usuario, PaymentMethod.CARD, null, "EUR",
                new BigDecimal("50.00"), UUID.randomUUID().toString(), null);

        // Se cobra lo que el cliente escribió, ni un céntimo más: no se reconvierte EUR→USD→EUR.
        assertThat(pago.getSettlementCurrency()).isEqualTo("EUR");
        assertThat(pago.getSettlementAmount()).isEqualByComparingTo("50.00");
        // El saldo se acredita en el USD canónico: 50 / 0,92 = 54,3478 → 5.435 céntimos.
        assertThat(pago.getAmountUsdCents()).isEqualTo(5435L);
    }

    @Test
    @DisplayName("Recarga con tarjeta en dólares: liquida en dólares por el mismo importe (USD→USD)")
    void recargaConTarjetaEnDolares_liquidaEnDolares() {
        UUID usuario = sembrarUsuario("recarga-usd@nx036.local");

        Payment pago = paymentUseCase.initiateRecharge(usuario, PaymentMethod.CARD, null, "USD",
                new BigDecimal("50.00"), UUID.randomUUID().toString(), null);

        assertThat(pago.getSettlementCurrency()).isEqualTo("USD");
        assertThat(pago.getSettlementAmount()).isEqualByComparingTo("50.00");
        assertThat(pago.getAmountUsdCents()).isEqualTo(5000L);
    }

    @Test
    @DisplayName("Recarga en cualquier otra divisa: liquida en dólares (SEK→USD) sin perder céntimos")
    void recargaEnOtraDivisa_liquidaEnDolares() {
        UUID usuario = sembrarUsuario("recarga-sek@nx036.local");

        // 500 SEK / 10 = 50,0000 USD exactos: la corona se eligió con tasa redonda para que el importe
        // canónico no tenga cola decimal y el céntimo perdido, si lo hubiera, se viera a simple vista.
        Payment pago = paymentUseCase.initiateRecharge(usuario, PaymentMethod.CARD, null, "SEK",
                new BigDecimal("500.00"), UUID.randomUUID().toString(), null);

        assertThat(pago.getSettlementCurrency()).isEqualTo("USD");
        assertThat(pago.getSettlementAmount()).isEqualByComparingTo("50.00");
        assertThat(pago.getAmountUsdCents()).isEqualTo(5000L);
    }

    @Test
    @DisplayName("PayPal en euros liquida en dólares: el equivalente exacto del canónico")
    void recargaPayPalEnEuros_liquidaEnDolares() {
        UUID usuario = sembrarUsuario("recarga-paypal@nx036.local");

        Payment pago = paymentUseCase.initiateRecharge(usuario, PaymentMethod.PAYPAL, null, "EUR",
                new BigDecimal("50.00"), UUID.randomUUID().toString(), null);

        // Solo Stripe puede cobrar en euros; PayPal liquida siempre en la divisa canónica.
        assertThat(pago.getSettlementCurrency()).isEqualTo("USD");
        assertThat(pago.getAmountUsdCents()).isEqualTo(5435L);
        assertThat(pago.getSettlementAmount()).isEqualByComparingTo("54.35");
    }

    /* ==================================================================================== */
    /* 5. Geolocalización: divisa por defecto según país                                    */
    /* ==================================================================================== */

    @Test
    @DisplayName("La divisa por país sale de currency_rate; un país sin divisa configurada cae a USD")
    void divisaPorPais_yPaisSinDivisaCaeADolares() {
        // El mapa país → divisa es la columna country_code de currency_rate, y solo cuentan las ACTIVAS.
        assertThat(divisaActivaDePais("ES")).isEqualTo("EUR");
        assertThat(divisaActivaDePais("JP")).isEqualTo("JPY");
        assertThat(divisaActivaDePais("CN")).isEqualTo("CNY");
        // Reino Unido tiene divisa en la tabla, pero DESACTIVADA: no puede ser la divisa por defecto.
        assertThat(divisaActivaDePais("GB")).isEqualTo("USD");
        // Vietnam no está configurado en absoluto → el valor por defecto del sistema.
        assertThat(divisaActivaDePais("VN")).isEqualTo("USD");

        // Y el endpoint público que consume el front expone ese país junto a cada divisa activa.
        JsonNode activas = json(get("/api/currency/rates", null));
        List<String> codigos = new ArrayList<>();
        activas.forEach(nodo -> codigos.add(nodo.get("code").asText()));
        assertThat(codigos).contains("EUR", "USD", "JPY").doesNotContain("GBP");
    }

    @Test
    @Disabled("En esta rama no existe /api/geo (ni lectura de CF-IPCountry): la detección por IP se "
            + "añadió después. Cuando exista, este test debe pedir /api/geo con CF-IPCountry=ES y esperar "
            + "EUR, y con CF-IPCountry=VN esperar USD.")
    @DisplayName("GEO: /api/geo devuelve la divisa del país por IP y USD si no está configurada")
    void geoDevuelveDivisaDelPaisPorIp() {
        // Sin endpoint que probar; el contrato queda escrito arriba para cuando se porte.
    }

    /* ==================================================================================== */
    /* 6. Divisa desactivada, inexistente o cabecera con basura                             */
    /* ==================================================================================== */

    @Test
    @DisplayName("Cabecera X-Currency vacía, ausente o en minúsculas: se normaliza sin romper")
    void cabeceraXCurrency_vaciaAusenteOEnMinusculas() {
        // Ausente → USD por defecto.
        assertThat(
                json(get(DETALLE.replace("{id}", referencia.id().toString()), null)).get("displayFormatted").asText())
                .isEqualTo("$20.00");
        // Vacía → USD por defecto (no revienta ni deja el importe a cero).
        assertThat(fichaEnDivisa("").get("displayFormatted").asText()).isEqualTo("$20.00");
        // En minúsculas → se normaliza a mayúsculas: es la misma divisa.
        assertThat(fichaEnDivisa("eur").get("displayFormatted").asText()).isEqualTo("18,40" + NBSP + "€");
        // El recorte de espacios se comprueba en el ThreadLocal y no por HTTP: Netty rechaza de plano un
        // valor de cabecera que empiece por espacio, así que ese caso no llega nunca a la aplicación.
        CurrencyHolder.set("  eur  ");
        assertThat(CurrencyHolder.get()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("Cabecera X-Currency con basura: cae a dólares, con el importe y la etiqueta en dólares")
    void cabeceraXCurrency_conBasura() {
        // «###» no es un código de divisa, así que no está en currency_rate y el filtro lo descarta: la
        // petición se sirve en la divisa por defecto. Antes la cabecera se aceptaba tal cual y el
        // formateador caía a su rama de códigos no ISO («$ 20.00», con el símbolo separado del número):
        // el importe era correcto pero se pintaba distinto que cualquier otra respuesta en dólares.
        JsonNode basura = fichaEnDivisa("###");
        assertThat(basura.get("displayCurrency").asText()).isEqualTo("USD");
        assertThat(basura.get("displayPrice").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(basura.get("displayFormatted").asText()).isEqualTo("$20.00");
        assertThat(basura.get("displaySymbol").asText()).isEqualTo("$");

        // XYZ sí es un código con forma de ISO 4217, pero tampoco está sembrado: mismo desenlace. Lo que
        // decide es que la divisa EXISTA en la tabla y esté activa, no que el código parezca válido.
        JsonNode inventada = fichaEnDivisa("XYZ");
        assertThat(inventada.get("displayCurrency").asText()).isEqualTo("USD");
        assertThat(inventada.get("displayPrice").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(inventada.get("displayFormatted").asText()).isEqualTo("$20.00");
    }

    @Test
    @DisplayName("Una divisa ISO sin tasa cae a dólares: importe Y etiqueta en la misma moneda")
    void divisaIsoSinTasa_caeADolaresImporteYEtiqueta() {
        // CHF no está sembrada en currency_rate. Hasta el 14-ago-2026 la cabecera se aceptaba sin
        // comprobar nada: la conversión no encontraba tasa y devolvía el importe en DÓLARES tal cual
        // (20,00), pero el formateador SÍ reconocía CHF como ISO 4217 y lo etiquetaba como francos. Al
        // cliente se le enseñaban «CHF20.00» cuando 20 USD no son 20 CHF — un precio que miente, y que
        // ni el cliente ni el soporte podían detectar mirando la pantalla.
        //
        // Ahora el filtro solo acepta la divisa si existe y está activa, así que importe y etiqueta van
        // SIEMPRE en la misma moneda: 20,00 USD pintados como dólares.
        JsonNode sinTasa = fichaEnDivisa("CHF");
        assertThat(sinTasa.get("displayCurrency").asText()).isEqualTo("USD");
        assertThat(sinTasa.get("displayPrice").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(sinTasa.get("displayFormatted").asText()).isEqualTo("$20.00");
        assertThat(sinTasa.get("displaySymbol").asText()).isEqualTo("$");
    }

    @Test
    @DisplayName("Una divisa DESACTIVADA no convierte: cae a dólares aunque tenga tasa en la tabla")
    void divisaDesactivada_caeADolares() {
        // GBP está en currency_rate con tasa 0,80 pero con active = false, así que NO aparece en el
        // selector de divisas del escaparate. Antes la conversión leía la caché sin mirar el flag: quien
        // mandara la cabecera a mano —o arrastrara la preferencia de antes de desactivarla— seguía
        // viendo 16,00 £, precios en una moneda que la tienda ya no vende.
        //
        // Desactivar una divisa tiene que apagarla de verdad, no solo esconderla del selector.
        JsonNode enLibras = fichaEnDivisa("GBP");
        assertThat(enLibras.get("displayCurrency").asText()).isEqualTo("USD");
        assertThat(enLibras.get("displayPrice").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(enLibras.get("displayFormatted").asText()).isEqualTo("$20.00");
        // La tasa sigue en la tabla y sigue siendo correcta: lo que se corta es SERVIR precios con ella.
        assertThat(currencyRateService.usdTo(new BigDecimal("20.00"), "GBP")).isEqualByComparingTo("16.00");
    }

    @Test
    @DisplayName("Convertir DESDE una divisa sin tasa es un fallo de configuración (500), no un 404")
    void convertirDesdeDivisaSinTasa_fallaDeFormaControlada() {
        // La dirección contraria (moneda del proveedor → USD) NO puede inventarse un 1:1: cobrar
        // 80 «CHF» como si fueran 80 dólares sería un error de dinero, así que se corta.
        //
        // Lo que cambió es CÓMO se corta. Antes lanzaba NotFoundException, que el manejador traduce a
        // 404: al faltar la tasa de CNY el catálogo entero respondía «no existe», y ni el cliente ni el
        // panel podían saber que lo que fallaba era la tabla de tasas. Que falte la tasa de la divisa de
        // ORIGEN es configuración ausente en el servidor, no un recurso que el cliente haya pedido mal:
        // como IllegalStateException sale con 500 y queda en el log de errores, que es donde se mira.
        assertThatThrownBy(() -> currencyRateService.toUsd(new BigDecimal("80.00"), "CHF"))
                .isInstanceOf(IllegalStateException.class).isNotInstanceOf(NotFoundException.class)
                .hasMessageContaining("CHF").hasMessageContaining("currency_rate");
        // Y hacia el display nunca devuelve null ni cero: devuelve el importe canónico.
        assertThat(currencyRateService.usdTo(new BigDecimal("80.00"), "CHF")).isEqualByComparingTo("80.00");
    }

    /* ==================================================================================== */
    /* 7. Casos borde de redondeo                                                           */
    /* ==================================================================================== */

    @Test
    @DisplayName("Tasa con decimales infinitos: se redondea UNA sola vez, al final, y siempre igual")
    void tasaConDecimalesInfinitos_redondeaUnaSolaVezYSiempreIgual() {
        // Dividir por 3 (MXN) no termina nunca. La conversión a USD trabaja con 4 decimales.
        BigDecimal aDolares = currencyRateService.toUsd(new BigDecimal("100.00"), "MXN");
        assertThat(aDolares).isEqualByComparingTo("33.3333");

        // Multiplicar por 0,33333333 (PLN) tampoco: 100 × 0,33333333 = 33,333333 → 33,33.
        BigDecimal aZlotys = currencyRateService.usdTo(new BigDecimal("100.00"), "PLN");
        assertThat(aZlotys).isEqualByComparingTo("33.33");

        // Determinismo: la misma entrada da SIEMPRE la misma salida (nada de acumular estado).
        for (int i = 0; i < 5; i++) {
            assertThat(currencyRateService.toUsd(new BigDecimal("100.00"), "MXN")).isEqualByComparingTo("33.3333");
            assertThat(currencyRateService.usdTo(new BigDecimal("100.00"), "PLN")).isEqualByComparingTo("33.33");
        }

        // Y un único redondeo al final: 240 CNY → 30 USD → margen 100 % → 60 USD → 60 × 0,33333333
        // = 19,9999998 → 20,00. Redondear antes (30 → 10,00 zł ×2) daría 20,00 igual, pero por otro
        // camino; lo que se fija aquí es que el resultado publicado es 20,00 exacto.
        Producto tercios = sembrarProducto("producto-tercios", "TERCIO-1", "240.0000", "0.0000", "0.0000");
        JsonNode ficha = fichaAdmin(tercios.id(), "PLN");
        assertThat(ficha.get("retailUsd").decimalValue()).isEqualByComparingTo("60.00");
        assertThat(ficha.get("displayPrice").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(ficha.get("displayFormatted").asText()).isEqualTo("20,00" + NBSP + "zł");
    }

    @Test
    @DisplayName("Importes de un céntimo, de cero y muy grandes se convierten sin sorpresas")
    void importesDeUnCentimoCeroYMuyGrandes() {
        // Un céntimo de dólar en euros: 0,0092 → 0,01 (HALF_UP redondea hacia arriba desde 0,005).
        assertThat(currencyRateService.usdTo(new BigDecimal("0.01"), "EUR")).isEqualByComparingTo("0.01");
        assertThat(currencyRateService.formatDisplay(new BigDecimal("0.01"), "EUR")).isEqualTo("0,01" + NBSP + "€");

        // Un céntimo de dólar en yenes: 1,50 ¥ → 2 (el yen no admite decimales; HALF_UP sube el 0,5).
        assertThat(currencyRateService.usdTo(new BigDecimal("0.01"), "JPY")).isEqualByComparingTo("2");

        // Medio céntimo: en euros desaparece (0,0046 → 0,00) y en dólares sube a 0,01. Es el borde
        // exacto de HALF_UP, el que decide si un pedido de muchas líneas se desvía o no.
        assertThat(currencyRateService.usdTo(new BigDecimal("0.005"), "EUR")).isEqualByComparingTo("0.00");
        assertThat(currencyRateService.usdTo(new BigDecimal("0.005"), "USD")).isEqualByComparingTo("0.01");

        // Cero es cero en todas las divisas: nunca null, nunca un símbolo suelto.
        assertThat(currencyRateService.usdTo(BigDecimal.ZERO, "EUR")).isEqualByComparingTo("0.00");
        assertThat(currencyRateService.usdTo(BigDecimal.ZERO, "JPY")).isEqualByComparingTo("0");
        assertThat(currencyRateService.formatDisplay(new BigDecimal("0.00"), "USD")).isEqualTo("$0.00");
        assertThat(currencyRateService.formatDisplay(new BigDecimal("0"), "JPY")).isEqualTo(YEN_ANCHO + "0");

        // Muy grande: 99.999.999,99 × 0,92 = 91.999.999,9908 → 91.999.999,99 (sin desbordar ni perder
        // precisión: BigDecimal, no double).
        assertThat(currencyRateService.usdTo(new BigDecimal("99999999.99"), "EUR")).isEqualByComparingTo("91999999.99");
        assertThat(currencyRateService.formatDisplay(new BigDecimal("91999999.99"), "EUR"))
                .isEqualTo("91.999.999,99" + NBSP + "€");
    }

    @Test
    @DisplayName("Divisa sin decimales (JPY): la unidad y la línea cuadran entre sí")
    void divisaSinDecimales_unidadYLineaCuadran() {
        // El fallo histórico: el importe se guardaba con céntimos y se pintaba redondeado, así que el
        // cliente veía 2.776 ¥ la unidad y 11.102 ¥ por cuatro. Redondeando a los decimales REALES de
        // la moneda, unidad e importe de línea vuelven a cuadrar.
        //
        // Aquí unidad × cantidad SÍ da el importe de línea, pero por una razón concreta: 20 USD son
        // 3.000 ¥ exactos, sin cola decimal que redondear. La regla general es la otra —la línea se
        // multiplica en dólares y se convierte una sola vez, ver
        // precioDeMedioCentimo_seRedondeaUnaSolaVezYNoDesvia—; lo que se comprueba en este caso es que
        // el yen no arrastra decimales inexistentes por ninguno de los dos caminos.
        JsonNode carrito = json(cotizarCarrito("JPY", referencia, 4));
        BigDecimal unidad = carrito.get("items").get(0).get("unit").decimalValue();
        BigDecimal linea = carrito.get("items").get(0).get("lineTotal").decimalValue();

        assertThat(unidad).isEqualByComparingTo("3000");
        assertThat(linea).isEqualByComparingTo("12000");
        assertThat(linea).isEqualByComparingTo(unidad.multiply(new BigDecimal("4")));
        assertThat(carrito.get("items").get(0).get("lineTotalFormatted").asText()).isEqualTo(YEN_ANCHO + "12,000");
        // Y sin parte decimal en ninguno de los dos: el yen no tiene céntimos.
        assertThat(carrito.get("items").get(0).get("unitFormatted").asText()).doesNotContain(".");
    }

    @Test
    @DisplayName("El redondeo no se acumula: 100 unidades del mismo precio no desvían el total")
    void redondeoNoSeAcumula_cienUnidadesDelMismoPrecio() {
        // Con un precio que convierte exacto (18,40 €) la línea de 100 unidades tiene que ser
        // exactamente 100 × 18,40 = 1.840,00 €, sin arrastrar ni un céntimo por el camino.
        JsonNode carrito = json(cotizarCarrito("EUR", referencia, 100));
        assertThat(carrito.get("items").get(0).get("unit").decimalValue()).isEqualByComparingTo("18.40");
        assertThat(carrito.get("items").get(0).get("lineTotal").decimalValue()).isEqualByComparingTo("1840.00");
        assertThat(carrito.get("subtotal").decimalValue()).isEqualByComparingTo("1840.00");
        assertThat(carrito.get("subtotalFormatted").asText()).isEqualTo("1.840,00" + NBSP + "€");

        // Y el canónico en dólares convertido de una sola vez da lo MISMO: 2.000,00 USD × 0,92.
        assertThat(currencyRateService.usdTo(new BigDecimal("2000.00"), "EUR")).isEqualByComparingTo("1840.00");
    }

    @Test
    @DisplayName("Con precios de medio céntimo, 100 unidades NO desvían: se redondea una sola vez, al final")
    void precioDeMedioCentimo_seRedondeaUnaSolaVezYNoDesvia() {
        // 0,60 CNY / 8 = 0,075 USD de coste → margen 100 % → 0,15 USD la unidad (canónico).
        // En euros: 0,15 × 0,92 = 0,138 → 0,14 € por unidad (HALF_UP sube el medio céntimo).
        //
        // Hasta el 14-ago-2026 el importe de línea era «unitario redondeado × cantidad»: 0,14 € × 100 =
        // 14,00 €, cuando lo que se compra son 15,00 USD, que valen 13,80 €. Veinte céntimos de más, un
        // +1,45 % sistemático que la pasarela liquidaba de verdad, porque el medio céntimo redondeado
        // hacia arriba se multiplicaba por la cantidad en vez de compensarse.
        //
        // Ahora la línea multiplica en DÓLARES y convierte al final, una sola vez
        // ({@code OrderAmounts.lineSubtotal}): 100 × 0,15 USD = 15,00 USD × 0,92 = 13,80 € EXACTOS.
        Producto barato = sembrarProducto("producto-medio-centimo", "MEDIO-1", "0.6000", "0.0000", "0.0000");

        JsonNode ficha = fichaAdmin(barato.id(), "EUR");
        assertThat(ficha.get("retailUsd").decimalValue()).isEqualByComparingTo("0.15");
        // El unitario que se PINTA no cambia: es el precio que el cliente eligió y reconoce.
        assertThat(ficha.get("displayPrice").decimalValue()).isEqualByComparingTo("0.14");

        JsonNode carrito = json(cotizarCarrito("EUR", barato, 100));
        assertThat(carrito.get("items").get(0).get("unit").decimalValue()).isEqualByComparingTo("0.14");
        // La contrapartida de la corrección: el importe de línea ya NO es «lo que ves × la cantidad»,
        // por eso se publica junto al unitario para que el cliente pueda cuadrar el subtotal sumando.
        assertThat(carrito.get("items").get(0).get("lineTotal").decimalValue())
                .as("100 × 0,15 $ = 15,00 $ → 13,80 €; 0,14 € × 100 = 14,00 € era el cobro inflado")
                .isEqualByComparingTo("13.80");
        assertThat(carrito.get("subtotal").decimalValue()).isEqualByComparingTo("13.80");

        // Canónico: 100 × 15 céntimos = 1.500 céntimos = 15,00 USD = 13,80 €.
        UUID comprador = UUID.randomUUID();
        CheckoutPreviewService.Preview canonico = enDivisa("EUR", () -> checkoutPreviewService.compute("ES", null,
                List.of(new CheckoutPreviewService.Line(barato.id(), barato.variantId(), 100)), comprador));
        assertThat(canonico.subtotalUsdCents()).isEqualTo(1500);
        assertThat(currencyRateService.usdTo(new BigDecimal("15.00"), "EUR")).isEqualByComparingTo("13.80");

        // Desviación CERO sobre el canónico: es EL número que fija esta prueba. Cualquier otro valor
        // significa que alguien ha vuelto a redondear el unitario antes de multiplicar.
        assertThat(canonico.subtotalDisplay().subtract(new BigDecimal("13.80")))
                .as("desviación del subtotal mostrado sobre el canónico convertido de una vez")
                .isEqualByComparingTo("0.00");

        // Y lo que ve en el carrito es lo que ve en el resumen del checkout: el mismo número.
        assertThat(canonico.subtotalDisplay()).isEqualByComparingTo(carrito.get("subtotal").decimalValue());
    }

    @Test
    @DisplayName("Ida y vuelta USD→EUR→USD devuelve el mismo importe; con tasa periódica la desviación es conocida")
    void idaYVueltaEntreDivisas() {
        BigDecimal cien = new BigDecimal("100.00");

        // Tasas que dividen exacto: la vuelta es idéntica al céntimo.
        assertThat(currencyRateService.toUsd(currencyRateService.usdTo(cien, "EUR"), "EUR"))
                .isEqualByComparingTo("100.0000");
        assertThat(currencyRateService.toUsd(currencyRateService.usdTo(cien, "JPY"), "JPY"))
                .isEqualByComparingTo("100.0000");
        assertThat(currencyRateService.toUsd(currencyRateService.usdTo(cien, "MXN"), "MXN"))
                .isEqualByComparingTo("100.0000");

        // Con tasa periódica (0,33333333) la ida pierde información al redondear a 2 decimales:
        // 100 → 33,33 zł → 33,33 / 0,33333333 = 99,9900 USD. La desviación esperada es de 1 céntimo
        // por cada 100 USD; se documenta en vez de tolerarse con un "casi igual".
        BigDecimal vueltaPln = currencyRateService.toUsd(currencyRateService.usdTo(cien, "PLN"), "PLN");
        assertThat(vueltaPln).isEqualByComparingTo("99.9900");
        assertThat(cien.subtract(vueltaPln)).isEqualByComparingTo("0.0100");
    }

    @Test
    @DisplayName("El desglose del checkout suma exactamente el total en la divisa activa")
    void desgloseDelCheckout_sumaExactamenteElTotal() {
        // Sin país configurado no hay impuesto ni despacho: el desglose es 0 + 0 y el total es el
        // subtotal. Lo que se comprueba es que la resta/suma se hace con los MISMOS componentes ya
        // redondeados que se enseñan, y no con otros calculados aparte.
        CheckoutTotalsService.CheckoutTotals totales = checkoutTotalsService.compute("ES", null, 2000, 0, List.of());
        assertThat(totales.taxCents()).isZero();
        assertThat(totales.customsHandlingCents()).isZero();
        assertThat(totales.shippingCents()).isZero();
        assertThat(totales.totalCents(2000)).isEqualTo(2000);

        UUID comprador = UUID.randomUUID();
        CheckoutPreviewService.Preview preview = enDivisa("EUR", () -> checkoutPreviewService.compute("ES", null,
                List.of(new CheckoutPreviewService.Line(referencia.id(), referencia.variantId(), 3)), comprador));

        assertThat(preview.subtotalDisplay()).isEqualByComparingTo("55.20"); // 3 × 18,40
        assertThat(preview.discountDisplay()).isEqualByComparingTo("0.00");
        assertThat(preview.shippingDisplay()).isEqualByComparingTo("0.00");
        assertThat(preview.taxDisplay()).isEqualByComparingTo("0.00");
        assertThat(preview.totalDisplay()).isEqualByComparingTo("55.20");
        assertThat(preview.subtotalUsdCents()).isEqualTo(6000);
    }

    /* ==================================================================================== */
    /* Utilidades del test                                                                  */
    /* ==================================================================================== */

    /** Ficha del producto de referencia en la divisa dada (cabecera {@code X-Currency}). */
    private JsonNode fichaEnDivisa(String divisa) {
        return fichaEnDivisa(referencia.id(), divisa);
    }

    private JsonNode fichaEnDivisa(UUID productId, String divisa) {
        return json(get(DETALLE.replace("{id}", productId.toString()), divisa));
    }

    /**
     * Ficha vista por un ADMIN. Hace falta para leer {@code costUsd}, {@code retailUsd} y el desglose
     * base/IVA/envío: al cliente final se le sirven a null a propósito (no debe ver el coste ni el
     * margen), así que los importes canónicos solo se pueden comprobar con este rol.
     */
    private JsonNode fichaAdmin(UUID productId, String divisa) {
        return json(get(DETALLE.replace("{id}", productId.toString()), divisa, bearer(jwt.userToken("ADMIN"))));
    }

    private JsonNode fichaAdmin(String divisa) {
        return fichaAdmin(referencia.id(), divisa);
    }

    /** GET anónimo con (o sin) cabecera de divisa; devuelve el cuerpo como texto. */
    private String get(String uri, String divisa) {
        return get(uri, divisa, (String) null);
    }

    /** GET autenticado como el usuario dado (token de USER con ese {@code sub}). */
    private String get(String uri, String divisa, UUID usuario) {
        return get(uri, divisa, bearer(jwt.userToken(usuario, "comprador@nx036.local", "USER")));
    }

    private String get(String uri, String divisa, String autorizacion) {
        WebTestClient.RequestHeadersSpec<?> peticion = client.get().uri(uri);
        if (divisa != null) {
            peticion = peticion.header(CABECERA_DIVISA, divisa);
        }
        if (autorizacion != null) {
            peticion = peticion.header("Authorization", autorizacion);
        }
        return peticion.exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
    }

    /** POST a /api/catalog/cart-quote (permitAll) con una única línea. */
    private String cotizarCarrito(String divisa, Producto producto, int cantidad) {
        Map<String, Object> linea = new LinkedHashMap<>();
        linea.put("productId", producto.id().toString());
        linea.put("variantId", producto.variantId().toString());
        linea.put("quantity", cantidad);
        return client.post().uri(CARRITO).header(CABECERA_DIVISA, divisa).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(List.of(linea)).exchange().expectStatus().isOk().expectBody(String.class).returnResult()
                .getResponseBody();
    }

    /** POST a /api/shipping/quote (requiere autenticación) con una única línea. */
    private String previsualizarCheckout(String divisa, UUID comprador, Producto producto, int cantidad) {
        Map<String, Object> linea = new LinkedHashMap<>();
        linea.put("productId", producto.id().toString());
        linea.put("variantId", producto.variantId().toString());
        linea.put("quantity", cantidad);
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("country", "ES");
        cuerpo.put("region", null);
        cuerpo.put("items", List.of(linea));
        return client.post().uri(CHECKOUT).header(CABECERA_DIVISA, divisa)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Authorization", bearer(jwt.userToken(comprador, "comprador@nx036.local", "USER")))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(cuerpo).exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
    }

    private static JsonNode json(String cuerpo) {
        try {
            return JSON.readTree(cuerpo);
        } catch (JacksonException e) {
            throw new IllegalStateException("Respuesta no es JSON válido: " + cuerpo, e);
        }
    }

    /** Fila del listado paginado correspondiente al producto dado, o {@code null} si no aparece. */
    private static JsonNode buscarEnListado(JsonNode pagina, UUID productId) {
        JsonNode items = pagina.has("content") ? pagina.get("content") : pagina.get("items");
        if (items == null) {
            return null;
        }
        for (JsonNode fila : items) {
            if (productId.toString().equals(fila.get("id").asText())) {
                return fila;
            }
        }
        return null;
    }

    /**
     * Ejecuta código de servicio con una divisa activa concreta. Los servicios leen la divisa del
     * {@link CurrencyHolder} (que en producción rellena el filtro a partir de la cabecera); en una
     * llamada directa hay que ponerla a mano y quitarla después.
     */
    private <T> T enDivisa(String divisa, Supplier<T> accion) {
        CurrencyHolder.set(divisa);
        try {
            return accion.get();
        } finally {
            CurrencyHolder.clear();
        }
    }

    /** Divisa ACTIVA asociada a un país en {@code currency_rate}, o USD si no hay ninguna. */
    private String divisaActivaDePais(String codigoPais) {
        List<String> codigos = jdbcTemplate.queryForList(
                "SELECT code FROM currency_rate WHERE country_code = ? AND active = true", String.class, codigoPais);
        return codigos.isEmpty() ? "USD" : codigos.get(0);
    }

    /* ============================ Siembra de datos ============================ */

    /**
     * Tasas de laboratorio. Se insertan por SQL (no por la API de admin) para que el test controle el
     * valor EXACTO de cada tasa y pueda predecir todos los importes a mano.
     */
    private void sembrarDivisas() {
        insertarDivisa("USD", "US Dollar", "$", "US", "en-US", "1.00000000", true);
        insertarDivisa("EUR", "Euro", "€", "ES", "es-ES", TASA_EUR, true);
        insertarDivisa("JPY", "Japanese Yen", "¥", "JP", "ja-JP", TASA_JPY, true);
        insertarDivisa("CNY", "Chinese Yuan", "¥", "CN", "zh-CN", TASA_CNY, true);
        insertarDivisa("SEK", "Swedish Krona", "kr", "SE", "sv-SE", TASA_SEK, true);
        insertarDivisa("MXN", "Mexican Peso", "$", "MX", "es-MX", TASA_MXN, true);
        insertarDivisa("PLN", "Polish Zloty", "zł", "PL", "pl-PL", TASA_PLN, true);
        // Desactivada a propósito: no sale en el selector del escaparate.
        insertarDivisa("GBP", "British Pound", "£", "GB", "en-GB", TASA_GBP, false);
        // CHF NO se siembra: es el caso «tasa ausente en currency_rate».
    }

    private void insertarDivisa(String codigo, String nombre, String simbolo, String pais, String locale, String tasa,
            boolean activa) {
        jdbcTemplate.update("""
                INSERT INTO currency_rate (id, code, name, symbol, country_code, locale, rate_vs_usd, active,
                                           last_synced_at, created_at, updated_at)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, CAST(? AS NUMERIC), ?, now(), now(), now())
                """, codigo, nombre, simbolo, pais, locale, tasa, activa);
    }

    /**
     * Fuerza el refresco de la caché de tasas (TTL 5 min) sin esperar. {@code applyBulkSync} es la
     * única puerta pública que releé la tabla entera: se le pasan las MISMAS tasas ya insertadas, así
     * que no cambia ningún dato — solo repuebla la caché con lo que hay en la BD tras el TRUNCATE.
     */
    private void refrescarCacheDeDivisas() {
        Map<String, BigDecimal> tasas = new HashMap<>();
        tasas.put("USD", new BigDecimal("1.00000000"));
        tasas.put("EUR", new BigDecimal(TASA_EUR));
        tasas.put("JPY", new BigDecimal(TASA_JPY));
        tasas.put("CNY", new BigDecimal(TASA_CNY));
        tasas.put("SEK", new BigDecimal(TASA_SEK));
        tasas.put("MXN", new BigDecimal(TASA_MXN));
        tasas.put("PLN", new BigDecimal(TASA_PLN));
        tasas.put("GBP", new BigDecimal(TASA_GBP));
        currencyRateService.applyBulkSync(tasas);
    }

    /** Regla de margen GLOBAL del canal escaparate. Con el 100 % el precio de venta es el coste ×2. */
    private void sembrarMargenGlobal(String porcentaje) {
        jdbcTemplate.update("""
                INSERT INTO price_rule (id, scope, scope_id, margin_type, margin_value, active, position,
                                        channel, description, created_at, updated_at)
                VALUES (gen_random_uuid(), 'GLOBAL', NULL, 'PERCENTAGE', CAST(? AS NUMERIC), true, 0,
                        'STOREFRONT', 'Margen de laboratorio del test de divisas', now(), now())
                """, porcentaje);
        // La caché de reglas también sobrevive al TRUNCATE (TTL 5 min): sin invalidarla el precio se
        // calcularía con la regla del 35 % que sembró Liquibase y ya no existe.
        marginService.invalidateCache();
    }

    /**
     * Producto vendible por el escaparate: ACTIVE, con imagen espejada (el listado exige {@code cdn_url}
     * no nulo) y con una variante activa al mismo precio que la base.
     *
     * @param basePriceCny precio de proveedor en CNY; con la tasa 8 el coste en USD sale exacto
     */
    private Producto sembrarProducto(String slug, String externalId, String basePriceCny, String ivaCny,
            String shippingCny) {
        UUID supplierId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO supplier (id, external_id, source, name, country, created_at, updated_at)
                VALUES (?, ?, '1688', 'Proveedor de prueba', 'CN', now(), now())
                """, supplierId, "SUP-" + externalId);

        UUID categoryId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO category (id, slug, source, external_id, name_zh, position, active, created_at, updated_at)
                VALUES (?, ?, '1688', ?, '测试', 0, true, now(), now())
                """, categoryId, "categoria-" + slug, "CAT-" + externalId);

        UUID productId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO product (id, slug, external_id, source, supplier_id, category_id, title_zh,
                                     base_price, currency, iva_cny, shipping_cny, moq, status,
                                     weight_grams, created_at, updated_at)
                VALUES (?, ?, ?, '1688', ?, ?, '测试产品', CAST(? AS NUMERIC), 'CNY', CAST(? AS NUMERIC),
                        CAST(? AS NUMERIC), 1, 'ACTIVE', 500, now(), now())
                """, productId, slug, externalId, supplierId, categoryId, basePriceCny, ivaCny, shippingCny);

        jdbcTemplate.update("""
                INSERT INTO product_image (id, product_id, position, role, source_url, cdn_url, mirror_status,
                                           created_at, updated_at)
                VALUES (gen_random_uuid(), ?, 0, 'MAIN', 'https://origen.invalid/a.jpg',
                        'https://cdn.invalid/a.jpg', 'MIRRORED', now(), now())
                """, productId);

        UUID variantId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO product_variant (id, product_id, external_id, sku, title, price, stock, active,
                                             weight_grams, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'Variante única', CAST(? AS NUMERIC), 100, true, 500, now(), now())
                """, variantId, productId, "V-" + externalId, "SKU-" + externalId, basePriceCny);

        return new Producto(productId, variantId);
    }

    /** Usuario mínimo (la recarga exige que exista en {@code users} antes de crear la wallet). */
    private UUID sembrarUsuario(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, email, role, active, language, created_at, updated_at)
                VALUES (?, ?, 'USER', true, 'es', now(), now())
                """, id, email);
        return id;
    }

    /**
     * Pedido ya cobrado por {@code totalUsdCents} céntimos de dólar canónicos, con una única línea. Se
     * siembra por SQL porque lo que se verifica es la CONVERSIÓN del importe ya cobrado, no el flujo de
     * compra (que tiene su propia certificación).
     */
    private UUID sembrarPedidoCobrado(UUID comprador, int totalUsdCents) {
        UUID direccionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO address (id, full_name, line1, city, state, postal_code, country, created_at)
                VALUES (?, 'Comprador de prueba', 'Calle Falsa 123', 'Madrid', 'M', '28001', 'ES', now())
                """, direccionId);

        UUID pedidoId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO customer_order (id, order_number, user_id, shipping_address_id, status,
                                            subtotal_cents, shipping_cents, tax_cents, discount_cents,
                                            total_cents, currency, source, fulfillment_attempts,
                                            placed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'PAID', ?, 0, 0, 0, ?, 'USD', 'PLATFORM', 0, now(), now(), now())
                """, pedidoId, "TEST-" + pedidoId.toString().substring(0, 8), comprador, direccionId, totalUsdCents,
                totalUsdCents);

        jdbcTemplate.update("""
                INSERT INTO order_item (id, order_id, product_id, variant_id, title_snapshot, sku_snapshot,
                                        unit_price_cents, cost_cents, quantity, line_total_cents)
                VALUES (gen_random_uuid(), ?, ?, ?, 'Producto de prueba', 'SKU-REF-1', ?, 1000, 1, ?)
                """, pedidoId, referencia.id(), referencia.variantId(), totalUsdCents, totalUsdCents);

        return pedidoId;
    }

    /**
     * Vacía las cachés de Spring (Caffeine). Sobreviven al TRUNCATE de {@link BaseIntegration} porque
     * viven en memoria: sin esto, un test leería la página de catálogo que dejó el anterior.
     */
    private void vaciarCachesDeAplicacion() {
        for (String nombre : cacheManager.getCacheNames()) {
            Cache cache = cacheManager.getCache(nombre);
            if (cache != null) {
                cache.clear();
            }
        }
    }
}
