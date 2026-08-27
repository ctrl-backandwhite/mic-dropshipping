package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.cj;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.application.service.CustomsValuationService;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.enums.TaxMode;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentFailure;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.FulfillmentResult;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.ParcelSpec;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.TrackingSnapshot;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * El transportista CJ Dropshipping visto desde la plataforma: qué cotiza, cómo despacha y cómo sigue.
 *
 * <p>Aquí se juntan las piezas que ya tienen sus propias pruebas —{@link CjFreightReader},
 * {@link CjTrackReader}, {@link CjInventoryLookup}—, así que lo que se comprueba <b>no es el parseo</b>
 * sino las decisiones que solo se pueden tomar con el pedido delante y que cuestan dinero si se toman
 * mal:
 *
 * <ul>
 *   <li><b>{@code isSandbox} en pre y producción.</b> Es la prueba que no se puede quitar: un pedido
 *       real emitido en modo prueba <b>no se envía nunca</b> y CJ contesta que todo fue bien, así que
 *       nadie se entera hasta que el cliente reclama. La bandera de configuración no basta —basta con
 *       que alguien copie un {@code .env} de local— y por eso el entorno manda sobre ella.</li>
 *   <li><b>La línea que se despacha es la que eligió el cliente</b>, no la más barata de ahora: entre
 *       el cobro y el despacho la tarifa cambia, y entonces se cobraría una cosa y se enviaría otra.</li>
 *   <li><b>Un SKU que no está depositado en CJ para el despacho con el SKU escrito</b>, porque su causa
 *       habitual es una errata al dar de alta el lote a mano y no se ve de ninguna otra forma.</li>
 *   <li><b>Un fallo de CJ no puede tumbar el checkout.</b> El enrutador tolera que un transportista se
 *       caiga, pero tolera un «sin opciones», no una excepción.</li>
 * </ul>
 */
class CjFulfillmentServiceTest {

    private static final String PAIS = "ES";
    private static final String NUMERO_DE_PEDIDO = "NX-2026-000123";
    private static final String SKU = "NX-CAM-AZ-M";
    private static final String VID = "2222222222222222222";

    /** La línea que eligió el cliente: la segunda de la cotización, que NO es la más barata. */
    private static final String OPCION_ELEGIDA = "1564849338719199233";
    private static final String NOMBRE_DE_LA_OPCION_ELEGIDA = "CJPacket Ordinary";

    /**
     * Dos opciones reales de las que CJ devolvió para España el 18-ago-2026, recortadas a los campos
     * que se usan. La primera es la más barata; la elegida en las pruebas es la segunda, a propósito.
     */
    private static final String PORTES = """
            {"code":200,"result":true,"message":"Success","data":[
              {"arrivalTime":"8-15","postage":6.13,"totalPostageFee":7.67,"error":"","errorEn":"",
               "optionId":"1868922929754472449",
               "option":{"id":"1868922929754472449","enName":"YunExpress Ordinary"},
               "channel":{"id":"12","enName":"promoción云途"}},
              {"arrivalTime":"4-8","postage":7.10,"totalPostageFee":8.83,"error":"","errorEn":"",
               "optionId":"1564849338719199233",
               "option":{"id":"1564849338719199233","enName":"CJPacket Ordinary"},
               "channel":{"id":"13","enName":"CJPacket"}}
            ]}
            """;

    /** Lo que devuelve {@code createOrderV3}: identificadores, nunca el número de seguimiento. */
    private static final String PEDIDO_CREADO = """
            {"code":200,"result":true,"message":"Success","data":{
              "orderId":"2408231029371914200","shipmentOrderId":"CJ2408231029371914201",
              "cjPayUrl":"https://cjdropshipping.com/pay/2408231029371914200"}}
            """;

    private static final String ENTREGADO = """
            {"code":200,"result":true,"message":"Success","data":[
              {"trackingNumber":"CJ2408231029371914201","logisticName":"CJPacket Ordinary",
               "trackingStatus":"Delivered","deliveryTime":"2026-08-28 07:04:04",
               "lastMileCarrier":"Correos","lastTrackNumber":"PQ0001234ES"}]}
            """;

    private final CjInventoryLookup inventario = mock(CjInventoryLookup.class);
    private final CustomsValuationService aduana = mock(CustomsValuationService.class);
    private final ProductRepository productos = mock(ProductRepository.class);

    private final CjSimulado cj = new CjSimulado();

    @BeforeEach
    void loQueDevuelvenLasPiezasDeAlLado() {
        when(inventario.variantIdDe(SKU)).thenReturn(Optional.of(VID));
        // Sin producto en la base, el agregador cuenta el peso por defecto: a estas pruebas les da igual
        // el peso exacto —lo prueba ParcelAggregator— y así no hay que sembrar un catálogo entero.
        when(productos.findById(any())).thenReturn(Optional.empty());
        when(aduana.carrierPrepaysVatFor(PAIS)).thenReturn(true);
        when(aduana.taxModeFor(PAIS)).thenReturn(TaxMode.DDP);
    }

    // ── Identidad y cobertura ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("se llama CJ, que es lo que el pedido guarda para saber a quién pedirle la guía")
    void seLlamaCj() {
        assertThat(servicio().nombre()).isEqualTo("CJ");
    }

    @Test
    @DisplayName("cubre cualquier destino con código de país de dos letras")
    void cubreCualquierDestinoConCodigoDeDosLetras() {
        CjFulfillmentService servicio = servicio();

        assertThat(servicio.isSupported("ES")).isTrue();
        assertThat(servicio.isSupported("us")).isTrue();
        assertThat(servicio.isSupported(" MX ")).isTrue();
    }

    @Test
    @DisplayName("lo que no es un código de país no se da por cubierto")
    void loQueNoEsUnCodigoDePaisNoSeDaPorCubierto() {
        CjFulfillmentService servicio = servicio();

        assertThat(servicio.isSupported(null)).isFalse();
        assertThat(servicio.isSupported("")).isFalse();
        assertThat(servicio.isSupported("E")).isFalse();
        assertThat(servicio.isSupported("ESP")).isFalse();
    }

    @Test
    @DisplayName("desactivado no cubre ningún destino y no se le pregunta nada a CJ")
    void desactivadoNoCubreNiLlama() {
        CjFulfillmentService servicio = servicio();
        ReflectionTestUtils.setField(servicio, "habilitado", false);

        assertThat(servicio.isSupported(PAIS)).isFalse();
        assertThat(servicio.quote(PAIS, ParcelSpec.ofWeight(500)).supported()).isFalse();
        assertThat(cj.rutas).as("con la integración apagada no se gasta ni una llamada").isEmpty();
    }

    @Test
    @DisplayName("no publica una lista de países: la cobertura del banner no sale de aquí")
    void noPublicaListaDePaises() {
        assertThat(servicio().supportedCountries()).isEmpty();
    }

    // ── Cotización ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("cotizar devuelve todas las opciones de CJ, con la más barata en la cabecera")
    void cotizaTodasLasOpciones() {
        ShippingQuote cotizacion = servicio().quote(PAIS, ParcelSpec.ofWeight(500));

        assertThat(cotizacion.supported()).isTrue();
        assertThat(cotizacion.options()).hasSize(2);
        assertThat(cotizacion.amountUsdCents())
                .as("la cabecera es lo que se cobra si el cliente no elige: la más barata")
                .isEqualTo(767);
        assertThat(cotizacion.options().get(0).amountUsdCents()).isEqualTo(767);
        assertThat(cotizacion.options().get(1).amountUsdCents()).isEqualTo(883);
    }

    @Test
    @DisplayName("el peso viaja en gramos y sin shippingMode ni platforms, que es lo medido contra CJ")
    void elPesoViajaEnGramos() {
        servicio().quote(PAIS, new ParcelSpec(500, 200, 150, 50, false));

        assertThat(cj.cuerpoDePortes).contains("\"weight\":500");
        assertThat(cj.cuerpoDePortes)
                .as("con shippingMode o platforms CJ devuelve cero opciones y dice que todo fue bien")
                .doesNotContain("shippingMode").doesNotContain("platforms");
        assertThat(cj.cuerpoDePortes).contains("\"srcAreaCode\":\"CN\"").contains("\"destAreaCode\":\"ES\"");
    }

    @Test
    @DisplayName("si CJ se cae, el checkout sigue en pie: sin opciones en vez de una excepción")
    void siCjSeCaeElCheckoutSigueEnPie() {
        cj.fallo = new IllegalStateException("connection reset");

        ShippingQuote cotizacion = servicio().quote(PAIS, ParcelSpec.ofWeight(500));

        assertThat(cotizacion.supported())
                .as("el enrutador tolera que un transportista no conteste, no que lance")
                .isFalse();
    }

    @Test
    @DisplayName("si CJ no cotiza nada, el destino no se ofrece")
    void siCjNoCotizaNadaElDestinoNoSeOfrece() {
        cj.portes = "{\"code\":200,\"result\":true,\"data\":[]}";

        assertThat(servicio().quote(PAIS, ParcelSpec.ofWeight(500)).supported()).isFalse();
    }

    // ── Despacho ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("con el nombre de la línea guardado en el pedido, NO se vuelve a cotizar")
    void conElNombreGuardadoNoSePreguntaOtraVez() {
        Order conNombre = pedido();
        conNombre.setShippingChannelName("CJPacket Ordinary");

        servicio().createShipments(conNombre);

        assertThat(cj.llamadasA("freightCalculate"))
                .as("el nombre se guardó al cobrar; volver a preguntarlo gasta una llamada de una API "
                        + "limitada a una por segundo, justo en el momento de despachar")
                .isZero();
        assertThat(cj.cuerpoDeCreacion).contains("\"logisticName\":\"CJPacket Ordinary\"");
    }

    @Test
    @DisplayName("si CJ ya no ofrece la línea, el pedido guardado se despacha igual")
    void elNombreGuardadoSalvaElDespachoAunqueLaLineaYaNoSeOfrezca() {
        Order conNombre = pedido();
        conNombre.setShippingChannelName("CJPacket Ordinary");
        cj.portes = "{\"code\":200,\"result\":true,\"data\":[]}";

        assertThat(servicio().createShipments(conNombre))
                .as("con el dato en el pedido, que CJ deje de ofrecer la línea ya no deja el envío parado")
                .hasSize(1);
    }

    @Test
    @DisplayName("un pedido anterior a la columna sí vuelve a preguntarle a CJ")
    void elPedidoAntiguoSiRecotiza() {
        Order sinNombre = pedido();
        sinNombre.setShippingChannelName(null);

        servicio().createShipments(sinNombre);

        assertThat(cj.llamadasA("freightCalculate"))
                .as("los pedidos de antes de v147 no traen el nombre: para ellos el respaldo sigue vivo")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("la guía se pide con NUESTRO número de pedido, que es lo que cruza el aviso del webhook")
    void laGuiaLlevaNuestroNumeroDePedido() {
        servicio().createShipments(pedido());

        assertThat(cj.cuerpoDeCreacion).contains("\"orderNumber\":\"" + NUMERO_DE_PEDIDO + "\"");
    }

    @Test
    @DisplayName("se manda la dirección completa del pedido, con el país también por su nombre")
    void mandaLaDireccionCompleta() {
        servicio().createShipments(pedido());

        assertThat(cj.cuerpoDeCreacion)
                .contains("\"shippingCountryCode\":\"ES\"")
                .contains("\"shippingCountry\":\"Spain\"")
                .contains("\"shippingProvince\":\"Madrid\"")
                .contains("\"shippingCity\":\"Madrid\"")
                .contains("\"shippingAddress\":\"Calle Mayor 1, 3º B\"")
                .contains("\"shippingCustomerName\":\"Ana Pérez\"")
                .contains("\"shippingPhone\":\"+34600111222\"")
                .contains("\"shippingZip\":\"28013\"");
    }

    @Test
    @DisplayName("una comilla en la calle no rompe el cuerpo ni deja el pedido sin despachar")
    void unaComillaEnLaCalleNoRompeElCuerpo() throws Exception {
        Order conComillas = pedido();
        conComillas.setShippingLine1("Rúa d'O \"Peirao\" 4");
        conComillas.setShippingLine2(null);

        servicio().createShipments(conComillas);

        assertThat(new ObjectMapper().readTree(cj.cuerpoDeCreacion).path("shippingAddress").asText())
                .as("la dirección la escribe el cliente: un apóstrofo no puede costar un despacho")
                .isEqualTo("Rúa d'O \"Peirao\" 4");
    }

    @Test
    @DisplayName("sin segunda línea la dirección no arrastra la coma de separación")
    void sinSegundaLineaNoHayComaSuelta() {
        Order sinSegundaLinea = pedido();
        sinSegundaLinea.setShippingLine2("   ");

        servicio().createShipments(sinSegundaLinea);

        assertThat(cj.cuerpoDeCreacion).contains("\"shippingAddress\":\"Calle Mayor 1\"");
    }

    @Test
    @DisplayName("se despacha por la línea que eligió el cliente, no por la más barata de ahora")
    void despachaPorLaLineaQueEligioElCliente() {
        servicio().createShipments(pedido());

        assertThat(cj.cuerpoDeCreacion)
                .as("la más barata es YunExpress Ordinary; el cliente pagó CJPacket Ordinary")
                .contains("\"logisticName\":\"" + NOMBRE_DE_LA_OPCION_ELEGIDA + "\"");
    }

    @Test
    @DisplayName("el país de salida de la mercancía es configurable")
    void elPaisDeSalidaEsConfigurable() {
        CjFulfillmentService servicio = servicio();
        ReflectionTestUtils.setField(servicio, "paisDeOrigen", "ES");

        servicio.createShipments(pedido());

        assertThat(cj.cuerpoDeCreacion).contains("\"fromCountryCode\":\"ES\"");
    }

    @Test
    @DisplayName("cada línea viaja con el identificador de variante que CJ exige")
    void cadaLineaViajaConSuVid() {
        servicio().createShipments(pedido());

        assertThat(cj.cuerpoDeCreacion).contains("\"products\":[{\"vid\":\"" + VID + "\",\"quantity\":2}]");
    }

    @Test
    @DisplayName("un SKU que no está depositado en CJ falla con el SKU escrito y sin emitir guía")
    void unSkuQueNoEstaEnCjFallaConElSkuEscrito() {
        when(inventario.variantIdDe(SKU)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio().createShipments(pedido()))
                .isInstanceOf(FulfillmentFailure.class)
                .as("la causa habitual es una errata al dar de alta el lote, y solo se ve con el código delante")
                .hasMessageContaining(SKU);
        assertThat(cj.llamadasA("createOrderV3")).isZero();
    }

    @ParameterizedTest(name = "en el perfil {0} isSandbox va a 0 aunque esté configurado a true")
    @ValueSource(strings = { "pre", "pro" })
    @DisplayName("en pre y en producción el modo prueba está prohibido, mande lo que mande la configuración")
    void enPreYProduccionNuncaSeEmiteEnModoPrueba(String perfil) {
        CjFulfillmentService servicio = servicio(perfil);

        servicio.createShipments(pedido());

        assertThat(cj.cuerpoDeCreacion)
                .as("un pedido real emitido en modo prueba no se envía nunca y nadie se entera")
                .contains("\"isSandbox\":0");
    }

    @Test
    @DisplayName("fuera de pre y producción sí se puede emitir en modo prueba")
    void fueraDePreYProduccionSiSePuedeProbar() {
        servicio().createShipments(pedido());

        assertThat(cj.cuerpoDeCreacion).contains("\"isSandbox\":1");
    }

    @Test
    @DisplayName("sin modo prueba configurado, el pedido es real aunque el entorno lo permita")
    void sinModoPruebaConfiguradoElPedidoEsReal() {
        CjFulfillmentService servicio = servicio();
        ReflectionTestUtils.setField(servicio, "modoPruebas", false);

        servicio.createShipments(pedido());

        assertThat(cj.cuerpoDeCreacion).contains("\"isSandbox\":0");
    }

    @Test
    @DisplayName("donde el transportista prepaga el IVA se declara con el IOSS de CJ")
    void dondeElTransportistaPrepagaElIvaSeUsaElIossDeCj() {
        servicio().createShipments(pedido());

        assertThat(cj.cuerpoDeCreacion).contains("\"iossType\":3");
    }

    @Test
    @DisplayName("fuera de la UE no hay régimen prepagado que declarar")
    void fueraDeLaUeNoHayRegimenPrepagado() {
        when(aduana.carrierPrepaysVatFor(PAIS)).thenReturn(false);

        servicio().createShipments(pedido());

        assertThat(cj.cuerpoDeCreacion).contains("\"iossType\":1");
    }

    @Test
    @DisplayName("el envío creado guarda los identificadores con los que CJ lo reconoce")
    void elEnvioGuardaLosIdentificadoresDeCj() {
        List<FulfillmentResult> envios = servicio().createShipments(pedido());

        assertThat(envios).hasSize(1);
        assertThat(envios.get(0).fulfillmentRef()).isEqualTo("2408231029371914200");
        assertThat(envios.get(0).trackingNumber()).isEqualTo("CJ2408231029371914201");
        assertThat(envios.get(0).productCode())
                .as("el canal cobrado se archiva para poder comprobar por dónde salió")
                .isEqualTo(OPCION_ELEGIDA);
        assertThat(envios.get(0).contents()).hasSize(1);
        assertThat(envios.get(0).contents().get(0).quantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("sin línea elegida no se llama a CJ: no se inventa por dónde sale el paquete")
    void sinLineaElegidaNoSeLlamaACj() {
        Order sinCanal = pedido();
        sinCanal.setShippingChannelCode(null);

        assertThatThrownBy(() -> servicio().createShipments(sinCanal))
                .isInstanceOf(FulfillmentFailure.class);
        assertThat(cj.llamadasA("createOrderV3")).isZero();
    }

    @Test
    @DisplayName("si CJ ya no ofrece la línea que se cobró, no se despacha por otra")
    void siCjYaNoOfreceLaLineaCobradaNoSeDespachaPorOtra() {
        Order otroCanal = pedido();
        otroCanal.setShippingChannelCode("9999999999999999999");

        assertThatThrownBy(() -> servicio().createShipments(otroCanal))
                .isInstanceOf(FulfillmentFailure.class);
        assertThat(cj.llamadasA("createOrderV3")).isZero();
    }

    @Test
    @DisplayName("si CJ rechaza el pedido, el fallo es controlado y el pedido queda en la bandeja")
    void siCjRechazaElPedidoElFalloEsControlado() {
        cj.creacion = "{\"code\":1600300,\"result\":false,\"message\":\"Parameter error\"}";

        assertThatThrownBy(() -> servicio().createShipments(pedido()))
                .isInstanceOf(FulfillmentFailure.class)
                .hasMessageContaining("Parameter error");
    }

    @Test
    @DisplayName("con la integración apagada no se emite una guía inventada")
    void conLaIntegracionApagadaNoSeEmiteGuiaInventada() {
        CjFulfillmentService servicio = servicio();
        ReflectionTestUtils.setField(servicio, "habilitado", false);

        assertThatThrownBy(() -> servicio.createShipments(pedido()))
                .isInstanceOf(FulfillmentFailure.class);
        assertThat(cj.rutas).isEmpty();
    }

    // ── Seguimiento ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("el estado de CJ se traduce al del pedido, y el hito se guarda en español")
    void traduceElEstadoDeCj() {
        TrackingSnapshot seguimiento = servicio().track("CJ2408231029371914201", null, PAIS);

        assertThat(seguimiento.currentStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(seguimiento.steps()).hasSize(1);
        assertThat(seguimiento.steps().get(0).description())
                .as("el texto de CJ llega en inglés y el timeline lo guarda traducido")
                .isEqualTo(CjTrackStatus.ENTREGADO.descripcion());
    }

    @Test
    @DisplayName("un estado que CJ acaba de inventarse no mueve el pedido")
    void unEstadoDesconocidoNoMueveElPedido() {
        cj.seguimiento = ENTREGADO.replace("Delivered", "Teleported");

        TrackingSnapshot seguimiento = servicio().track("CJ2408231029371914201", null, PAIS);

        assertThat(seguimiento.currentStatus()).isEqualTo(OrderStatus.FORWARDED);
        assertThat(seguimiento.steps()).isEmpty();
    }

    @Test
    @DisplayName("una incidencia deja el envío donde estaba y no escribe un hito sin texto")
    void laIncidenciaNoEscribeUnHitoSinTexto() {
        cj.seguimiento = ENTREGADO.replace("Delivered", "Exception");

        TrackingSnapshot seguimiento = servicio().track("CJ2408231029371914201", null, PAIS);

        assertThat(seguimiento.currentStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(seguimiento.steps())
                .as("no hay texto traducido para una incidencia, y el de «en tránsito» diría lo contrario")
                .isEmpty();
    }

    @Test
    @DisplayName("si CJ no responde, el sondeo no revienta: el pedido se queda donde estaba")
    void siCjNoRespondeElSondeoNoRevienta() {
        cj.fallo = new IllegalStateException("timeout");

        assertThatCode(() -> {
            TrackingSnapshot seguimiento = servicio().track("CJ2408231029371914201", null, PAIS);
            assertThat(seguimiento.currentStatus()).isEqualTo(OrderStatus.FORWARDED);
            assertThat(seguimiento.steps()).isEmpty();
        }).doesNotThrowAnyException();
    }

    // ── Impuestos ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("el modo de despacho fiscal sale de la tabla de países, igual que con YunExpress")
    void elModoFiscalSaleDeLaTablaDePaises() {
        when(aduana.taxModeFor("US")).thenReturn(TaxMode.DDU);

        assertThat(servicio().taxModeFor(PAIS)).isEqualTo(TaxMode.DDP);
        assertThat(servicio().taxModeFor("US")).isEqualTo(TaxMode.DDU);
    }

    // ── Andamiaje ────────────────────────────────────────────────────────────────────────────────

    /** Servicio en un entorno sin perfiles: es el local, donde el modo prueba sí está permitido. */
    private CjFulfillmentService servicio() {
        return servicio(new String[0]);
    }

    private CjFulfillmentService servicio(String... perfiles) {
        MockEnvironment entorno = new MockEnvironment();
        entorno.setActiveProfiles(perfiles);
        CjFulfillmentService servicio = new CjFulfillmentService(inventario, aduana, productos, entorno, cj);
        ReflectionTestUtils.setField(servicio, "habilitado", true);
        ReflectionTestUtils.setField(servicio, "modoPruebas", true);
        ReflectionTestUtils.setField(servicio, "paisDeOrigen", "CN");
        return servicio;
    }

    /** Un pedido a España de dos unidades del mismo SKU, con la línea de envío ya elegida. */
    private static Order pedido() {
        return Order.builder()
                .id(UUID.randomUUID())
                .orderNumber(NUMERO_DE_PEDIDO)
                .shippingChannelCode(OPCION_ELEGIDA)
                .shippingCountry(PAIS)
                .shippingFullName("Ana Pérez")
                .shippingPhone("+34600111222")
                .shippingLine1("Calle Mayor 1")
                .shippingLine2("3º B")
                .shippingCity("Madrid")
                .shippingState("Madrid")
                .shippingPostalCode("28013")
                .items(List.of(OrderItem.builder()
                        .id(UUID.randomUUID()).productId(UUID.randomUUID())
                        .skuSnapshot(SKU).quantity(2).unitPriceCents(1990).lineTotalCents(3980)
                        .build()))
                .build();
    }

    /**
     * CJ de mentira: apunta lo que se le manda y contesta lo que la prueba haya preparado.
     *
     * <p>Se sustituye la llamada entera y no solo la red para que las pruebas puedan mirar el
     * <b>cuerpo exacto</b> de cada petición: los dos fallos que ya costaron tiempo con esta API —el peso
     * en la unidad equivocada y el modo prueba en producción— se ven ahí y en ningún otro sitio.
     */
    private static final class CjSimulado implements CjFulfillmentService.LlamadaACj {

        private final List<String> rutas = new ArrayList<>();
        private String portes = PORTES;
        private String creacion = PEDIDO_CREADO;
        private String seguimiento = ENTREGADO;
        private RuntimeException fallo;
        private String cuerpoDePortes;
        private String cuerpoDeCreacion;

        @Override
        public String responder(String ruta, String cuerpo) {
            rutas.add(ruta);
            if (fallo != null) {
                throw fallo;
            }
            if (ruta.contains("freightCalculate")) {
                cuerpoDePortes = cuerpo;
                return portes;
            }
            if (ruta.contains("createOrderV3")) {
                cuerpoDeCreacion = cuerpo;
                return creacion;
            }
            return seguimiento;
        }

        private int llamadasA(String fragmentoDeRuta) {
            return (int) rutas.stream().filter(ruta -> ruta.contains(fragmentoDeRuta)).count();
        }
    }
}
