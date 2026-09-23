package com.nexaplatform.dropshipping.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.config.BaseIntegration;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.YunExpressClient;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.YunExpressFulfillmentService;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.YunExpressRequests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El prepago del IVA por el transportista: cuándo se le pide y cuándo no, decidido por la REGLA DEL PAÍS
 * que hay en la base de datos.
 *
 * <p><b>Qué está en juego.</b> La tienda vende DDP: cobra el IVA en el checkout y promete que al recibir
 * el paquete nadie le reclama nada al cliente. Esa promesa solo se cumple si YunExpress liquida el
 * impuesto con SU número IOSS, y eso se pide envío a envío con el servicio adicional {@code V1}
 * (云途预缴). Si falta, el paquete se despacha como si el impuesto no estuviera pagado y quien lo paga
 * otra vez en destino es el cliente —que ya pagó—. Y al revés: pedirlo donde el transportista no tiene
 * ese régimen hace que el alta del envío falle y el pedido se quede sin guía.
 *
 * <p><b>Por qué hace falta esta clase y no bastan las pruebas unitarias.</b>
 * {@code Cov04YunExpressShipmentTest} comprueba la decisión pasándole a mano una {@code CustomsValuation}
 * ya construida, y {@code YunExpressRequestsTest} comprueba que el JSON lleva los nombres correctos.
 * Ninguna de las dos toca lo que de verdad decide en producción: la columna
 * {@code country_customs_rule.carrier_prepays_vat} del destino. Entre esa columna y el cuerpo de la
 * petición hay un servicio, un repositorio y una valoración aduanera, y basta con que uno de los tres
 * deje de mirar el país para que TODOS los envíos —o ninguno— pidan el prepago sin que ninguna prueba
 * unitaria se entere.
 *
 * <p><b>El transportista se sustituye por un doble.</b> Crear un envío de verdad es una llamada a la API
 * de producción de YunExpress que emite una guía y gasta saldo de una cuenta real. Lo que se prueba aquí
 * es NUESTRA construcción de la petición: se captura el cuerpo que se le iba a mandar y se afirma sobre
 * él.
 */
@TestPropertySource(properties = {
        // Con el proveedor "apagado" el servicio genera envíos simulados y NO construye petición ninguna:
        // no habría nada que capturar. Las credenciales las da por buenas el doble del cliente.
        "nexadrop.yunexpress.enabled=true",
        // Canal fijo: sin él, cada alta llamaría antes a la simulación de tarifa para elegir el más
        // barato, y eso mete en la prueba una llamada que no tiene nada que ver con lo que se mide.
        "nexadrop.yunexpress.product-code=BPA",
        // El IOSS del comercio viaja en el mismo régimen que el prepago; sin número no se podría
        // comprobar que los dos aparecen y desaparecen juntos.
        "nexadrop.yunexpress.ioss-number=IM3720000000",
        // Límite REAL del canal contratado (BPA), para que un pedido de dos unidades se reparta en dos
        // bultos y se pueda comprobar que el prepago viaja en TODAS las guías, no solo en la primera.
        "nexadrop.yunexpress.max-parcel-weight-grams=600"})
class PrepaidVatShipmentIT extends BaseIntegration {

    /** Alta de envío en la Open Platform: la petición cuyo cuerpo se captura. */
    private static final String RUTA_ALTA = "/v1/order/package/create";

    /** Código del servicio adicional de prepago y su etiqueta, tal y como los nombra el transportista. */
    private static final String SERVICIO_PREPAGO = "V1";
    private static final String ETIQUETA_PREPAGO = "云途预缴";

    /** El IOSS configurado arriba; se comprueba que acompaña al prepago bajo la franquicia. */
    private static final String IOSS = "IM3720000000";

    /** Franquicia sembrada para todos los destinos de la clase, en dólares (ver {@link #sembrarRegla}). */
    private static final int FRANQUICIA_USD = 150;

    /** Valor intrínseco de un pedido normal: 45,00 $, cómodamente por debajo de la franquicia. */
    private static final int VALOR_BAJO_CENTS = 4_500;
    /** Valor intrínseco por encima de la franquicia: 200,00 $, donde el régimen simplificado no aplica. */
    private static final int VALOR_ALTO_CENTS = 20_000;

    /** Peso de cada unidad del producto sembrado. Dos unidades no caben en un bulto del canal. */
    private static final int PESO_UNIDAD_GRAMOS = 500;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** El transportista, sustituido: ver la nota de la clase. */
    @MockitoBean
    private YunExpressClient transportista;

    @Autowired
    private YunExpressFulfillmentService envios;

    @Autowired
    private PlatformTransactionManager transacciones;

    private UUID productoId;

    @BeforeEach
    void prepararTransportistaYCatalogo() {
        when(transportista.hasCredentials()).thenReturn(true);
        when(transportista.post(anyString(), any(Object.class))).thenReturn(respuestaConGuia());
        productoId = insertarProducto();
    }

    /* ==================================================================================
     *  A · La marca del país decide
     * ================================================================================== */

    @Test
    @DisplayName("a un destino de la UE con el IVA prepagado por el transportista, el envío pide el servicio V1")
    void elDestinoConIvaPrepagadoPideElServicioV1() {
        sembrarRegla("ES", "DDP", true);

        YunExpressRequests.CreateShipment alta = altaDeEnvio(pedido("ES", 1, VALOR_BAJO_CENTS));

        assertThat(alta.extraServices()).hasSize(1);
        assertThat(alta.extraServices().getFirst().extraCode()).isEqualTo(SERVICIO_PREPAGO);
        assertThat(alta.extraServices().getFirst().extraValue()).isEqualTo(ETIQUETA_PREPAGO);
        assertThat(alta.customsNumber()).as("bajo la franquicia el IOSS del comercio viaja con el prepago").isNotNull();
        assertThat(alta.customsNumber().iossCode()).isEqualTo(IOSS);
    }

    @Test
    @DisplayName("donde el canal YA va DDP, el transportista prepaga y NO se le pide ningún servicio")
    void elDestinoConCanalDdpPrepagaSinPedirServicio() {
        // Emiratos, Arabia Saudí, Canadá y México (19-ago-2026): el contrato de YunExpress dice que
        // nuestros canales FZZXR y THPHR ya van DDP en esos destinos y que el transportista cobra el
        // impuesto al remitente —5 %, 15 %, 18 % y 33,5 % del valor declarado—. O sea que SÍ prepaga,
        // pero NO por el IOSS: pedirle allí el servicio V1, que está definido como «prepago del IOSS de
        // la reforma fiscal de la UE», haría fallar el alta del envío y el pedido se quedaría sin guía.
        //
        // Por eso la marca de «prepaga» y el código del servicio son dos cosas distintas y viajan en dos
        // columnas: la primera dice que al cliente se le cobra aquí, la segunda qué hay que pedirle al
        // transportista, si es que hay que pedirle algo.
        sembrarRegla("AE", "DDP", true, null);

        YunExpressRequests.CreateShipment alta = altaDeEnvio(pedido("AE", 1, VALOR_BAJO_CENTS));

        assertThat(alta.extraServices()).as("el canal ya va DDP por contrato: pedir V1 aquí tumba el alta del envío")
                .isNullOrEmpty();
    }

    @Test
    @DisplayName("el servicio que se pide sale del PAÍS, no de una constante global")
    void elServicioSaleDelPais() {
        // Si el código viviera solo en la configuración, activar un país nuevo obligaría a desplegar, y
        // peor: se mandaría el mismo servicio a todos, que es justo lo que no se puede hacer.
        sembrarRegla("ES", "DDP", true, "V1");

        YunExpressRequests.CreateShipment alta = altaDeEnvio(pedido("ES", 1, VALOR_BAJO_CENTS));

        assertThat(alta.extraServices()).hasSize(1);
        assertThat(alta.extraServices().getFirst().extraCode()).isEqualTo("V1");
    }

    @Test
    @DisplayName("a un destino sin esa marca NO se pide el prepago: allí el transportista no liquida nada")
    void elDestinoSinLaMarcaNoPideElPrepago() {
        // Estados Unidos está en DDP como todos los destinos activos, pero fuera del régimen IOSS: pedir
        // ahí el prepago haría fallar el alta del envío y el pedido se quedaría sin guía.
        sembrarRegla("US", "DDP", false);

        YunExpressRequests.CreateShipment alta = altaDeEnvio(pedido("US", 1, VALOR_BAJO_CENTS));

        assertThat(alta.extraServices()).isNullOrEmpty();
    }

    @Test
    @DisplayName("lo que decide es la columna del país: con el mismo destino y el mismo pedido, cambiarla cambia la petición")
    void laColumnaDelPaisEsLaQueDecide() {
        // El caso que ninguna prueba unitaria puede dar por bueno: que el dato de la base llegue hasta el
        // cuerpo de la petición. Mismo país, mismo pedido, misma configuración: solo cambia la columna.
        sembrarRegla("DE", "DDP", false);
        despachar(pedido("DE", 1, VALOR_BAJO_CENTS));

        jdbcTemplate.update("UPDATE country_customs_rule SET carrier_prepays_vat = true,"
                + " updated_at = now() WHERE country_code = 'DE'");
        despachar(pedido("DE", 1, VALOR_BAJO_CENTS));

        List<YunExpressRequests.CreateShipment> altas = altasCapturadas();
        assertThat(altas).hasSize(2);
        assertThat(altas.getFirst().extraServices()).as("sin la marca, no se pide").isNullOrEmpty();
        assertThat(altas.getLast().extraServices()).as("con la marca, sí")
                .extracting(YunExpressRequests.ExtraService::extraCode).containsExactly(SERVICIO_PREPAGO);
    }

    @Test
    @DisplayName("un destino SIN regla aduanera configurada no pide el prepago: la falta de dato no lo inventa")
    void unDestinoSinReglaNoPideElPrepago() {
        // Suiza no tiene fila: la valoración sale neutra. Que un país sin configurar acabara pidiendo el
        // prepago sería lo peor de los dos mundos, porque el fallo aparecería al despachar y no al vender.
        YunExpressRequests.CreateShipment alta = altaDeEnvio(pedido("CH", 1, VALOR_BAJO_CENTS));

        assertThat(alta.extraServices()).isNullOrEmpty();
    }

    /* ==================================================================================
     *  B · Los dos límites del régimen: modo fiscal y franquicia
     * ================================================================================== */

    @Test
    @DisplayName("en DDU no se pide el prepago aunque el país lo tenga marcado: el impuesto lo paga quien recibe")
    void enDduNoSePideElPrepago() {
        // Pedirlo aquí cobraría el impuesto dos veces: una al comercio por adelantado y otra al
        // destinatario al recibir.
        sembrarRegla("MX", "DDU", true);

        YunExpressRequests.CreateShipment alta = altaDeEnvio(pedido("MX", 1, VALOR_BAJO_CENTS));

        assertThat(alta.extraServices()).isNullOrEmpty();
    }

    @Test
    @DisplayName("por encima de la franquicia no se pide prepago ni IOSS: el régimen simplificado deja de aplicar")
    void porEncimaDeLaFranquiciaNoSePidePrepagoNiIoss() {
        // Por encima del umbral el despacho es formal: el prepago no tiene dónde liquidarse y declarar el
        // IOSS hace que la aduana rechace la liquidación. Los dos van juntos, y aquí caen juntos.
        sembrarRegla("ES", "DDP", true);

        YunExpressRequests.CreateShipment alta = altaDeEnvio(pedido("ES", 1, VALOR_ALTO_CENTS));

        assertThat(alta.extraServices()).isNullOrEmpty();
        assertThat(alta.customsNumber()).isNull();
    }

    /* ==================================================================================
     *  C · Un pedido repartido en varios bultos
     * ================================================================================== */

    @Test
    @DisplayName("si el pedido se reparte en varios bultos, TODAS las guías piden el prepago, no solo la primera")
    void todasLasGuiasDelPedidoRepartidoPidenElPrepago() {
        // El límite de peso del canal parte el pedido en dos envíos independientes. Una guía sin el extra
        // es un paquete que llega con el IVA sin liquidar: el cliente paga en destino lo que ya pagó.
        sembrarRegla("ES", "DDP", true);

        List<YunExpressRequests.CreateShipment> altas = altasDeEnvio(pedido("ES", 2, VALOR_BAJO_CENTS));

        assertThat(altas).as("dos unidades de 500 g no caben en un bulto de 600 g").hasSize(2);
        assertThat(altas).allSatisfy(alta -> assertThat(alta.extraServices())
                .extracting(YunExpressRequests.ExtraService::extraCode).containsExactly(SERVICIO_PREPAGO));
    }

    /* ==================================================================================
     *  D · El interruptor de emergencia
     * ================================================================================== */

    /**
     * Con el código del servicio vacío en la configuración.
     *
     * <p>Es una clase anidada con SUS propiedades —y no un {@code setField} sobre el bean— justamente
     * porque lo que se quiere comprobar es que la propiedad se llama como el operador cree que se llama:
     * escribirle el campo por reflexión pasaría igual aunque el nombre de la propiedad estuviera mal, y
     * entonces el interruptor no funcionaría el día que hiciera falta usarlo.
     */
    @Nested
    @TestPropertySource(properties = {"nexadrop.yunexpress.enabled=true", "nexadrop.yunexpress.product-code=BPA",
            "nexadrop.yunexpress.ioss-number=IM3720000000", "nexadrop.yunexpress.max-parcel-weight-grams=600",
            // El interruptor: si la cuenta del transportista no tiene dado de alta el servicio, mandarlo
            // hace fallar el alta de TODOS los envíos, y eso hay que poder apagarlo sin desplegar.
            "nexadrop.yunexpress.prepaid-vat-service-code="})
    class ConElServicioDeshabilitado {

        @Test
        @DisplayName("vaciando la propiedad de configuración deja de pedirse el prepago, sin tocar el código")
        void vaciarLaPropiedadApagaElPrepago() {
            sembrarRegla("ES", "DDP", true);

            YunExpressRequests.CreateShipment alta = altaDeEnvio(pedido("ES", 1, VALOR_BAJO_CENTS));

            assertThat(alta.extraServices()).isNullOrEmpty();
        }

        @Test
        @DisplayName("apagar el prepago no arrastra al IOSS: el envío se sigue declarando con el número del comercio")
        void apagarElPrepagoNoSeLlevaPorDelanteElIoss() {
            // El interruptor tiene que ser quirúrgico. Si además dejara de declararse el IOSS, apagar un
            // servicio del transportista cambiaría el régimen fiscal del envío, que es otra decisión y
            // mucho más grave.
            sembrarRegla("ES", "DDP", true);

            YunExpressRequests.CreateShipment alta = altaDeEnvio(pedido("ES", 1, VALOR_BAJO_CENTS));

            assertThat(alta.customsNumber()).isNotNull();
            assertThat(alta.customsNumber().iossCode()).isEqualTo(IOSS);
        }
    }

    /* ==================================================================================
     *  Utilidades de la prueba
     * ================================================================================== */

    /** El cuerpo de la ÚNICA alta de envío que produce el pedido. */
    private YunExpressRequests.CreateShipment altaDeEnvio(Order pedido) {
        List<YunExpressRequests.CreateShipment> altas = altasDeEnvio(pedido);
        assertThat(altas).as("el pedido tenía que producir una sola guía").hasSize(1);
        return altas.getFirst();
    }

    /** Despacha el pedido y devuelve los cuerpos que se le iban a mandar al transportista, uno por bulto. */
    private List<YunExpressRequests.CreateShipment> altasDeEnvio(Order pedido) {
        despachar(pedido);
        return altasCapturadas();
    }

    /**
     * Despacha el pedido con el transportista sustituido por el doble.
     *
     * <p>Va dentro de una transacción porque es como se hace en producción
     * ({@code FulfillmentService.createShipment} es {@code @Transactional}): la declaración aduanera
     * recorre las traducciones del producto, que son perezosas, y sin sesión abierta no se podrían leer.
     */
    private void despachar(Order pedido) {
        new TransactionTemplate(transacciones).executeWithoutResult(estado -> envios.createShipments(pedido));
    }

    /**
     * Todos los cuerpos de alta capturados en la prueba, en el orden en que se enviaron. Son
     * ACUMULATIVOS: un caso que despacha dos veces los recibe los dos, que es justo lo que hace falta
     * para comparar el antes y el después de cambiar la regla del país.
     */
    private List<YunExpressRequests.CreateShipment> altasCapturadas() {
        ArgumentCaptor<Object> cuerpos = ArgumentCaptor.forClass(Object.class);
        verify(transportista, atLeastOnce()).post(eq(RUTA_ALTA), cuerpos.capture());
        return cuerpos.getAllValues().stream().map(YunExpressRequests.CreateShipment.class::cast).toList();
    }

    /** Respuesta del transportista dando la guía por creada; lo que devuelva no altera lo que se le mandó. */
    private static JsonNode respuestaConGuia() {
        try {
            return JSON.readTree("""
                    {"success":true,"result":{"waybill_number":"YT2621101299000001","tracking_number":"LX1ES"}}
                    """);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Respuesta de prueba mal formada", e);
        }
    }

    /**
     * Pedido listo para despachar a un destino.
     *
     * @param pais            destino, que es lo que hace mirar una regla aduanera u otra
     * @param unidades        unidades de la única línea (para forzar el reparto en bultos)
     * @param intrinsecoCents valor de los bienes que se declara: es sobre él sobre el que se mide la
     *                        franquicia, así que decide si el régimen simplificado aplica
     */
    private Order pedido(String pais, int unidades, int intrinsecoCents) {
        OrderItem linea = new OrderItem();
        linea.setProductId(productoId);
        linea.setQuantity(unidades);
        linea.setUnitPriceCents(intrinsecoCents / unidades);
        linea.setTitleSnapshot("Cotton T-shirt");
        // Nombre en chino: el transportista rechaza la guía si falta o si no lleva ideogramas.
        linea.setProductTitleZh("棉T恤");
        linea.setProductTitles(Map.of("en", "Cotton T-shirt"));
        linea.setSkuSnapshot("SKU-1");

        Order pedido = new Order();
        pedido.setId(UUID.randomUUID());
        pedido.setOrderNumber("NX-PREPAGO-" + pais);
        pedido.setCurrency("USD");
        pedido.setShippingCountry(pais);
        pedido.setShippingFullName("Ana López");
        pedido.setShippingLine1("Calle Mayor 1");
        pedido.setShippingCity("Zaragoza");
        pedido.setShippingPostalCode("50001");
        pedido.setSubtotalCents(intrinsecoCents);
        pedido.setDiscountCents(0);
        pedido.setItems(new ArrayList<>(List.of(linea)));
        return pedido;
    }

    /**
     * Regla aduanera del destino, que es lo que decide el prepago.
     *
     * <p>{@code BaseIntegration} vacía {@code country_customs_rule} antes de cada prueba, así que la fila
     * se siembra aquí. La franquicia va en DÓLARES —el importe legal son 150 EUR— para que el borde no
     * dependa de la tasa de cambio del día ni de la caché de divisas: lo que se mide es a qué lado del
     * umbral cae el pedido, no cuánto vale el umbral, que ya certifica {@code CustomsDutyIT}.
     */
    private void sembrarRegla(String pais, String modoFiscal, boolean prepagaElTransportista) {
        sembrarRegla(pais, modoFiscal, prepagaElTransportista, "V1");
    }

    /**
     * @param servicioDePrepago código que hay que pedirle al transportista, o {@code null} si el canal ya
     *                          va DDP por contrato y no hay que pedirle nada
     */
    private void sembrarRegla(String pais, String modoFiscal, boolean prepagaElTransportista,
            String servicioDePrepago) {
        jdbcTemplate.update(
                "INSERT INTO country_customs_rule (id, country_code, tax_mode,"
                        + " de_minimis_amount, de_minimis_currency, over_threshold_policy, handling_fee_cents,"
                        + " handling_percent_bps, carrier_prepays_vat, vat_prepay_service_code, active,"
                        + " created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'USD', 'SURCHARGE', 0, 0, ?, ?, true, now(), now())",
                UUID.randomUUID(), pais, modoFiscal, FRANQUICIA_USD, prepagaElTransportista, servicioDePrepago);
    }

    /**
     * Producto con lo que la declaración aduanera necesita: partida arancelaria, peso y traducción al
     * inglés. Sin peso no habría reparto en bultos que comprobar.
     */
    private UUID insertarProducto() {
        UUID id = UUID.randomUUID();
        String sufijo = id.toString().substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO product (id, slug, external_id, source, title_zh, status, moq,"
                        + " base_price, currency, shipping_cny, iva_cny, weight_grams, hs_code, customs_material,"
                        + " customs_usage, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'TEST', '棉T恤', 'ACTIVE', 1, 45.0000, 'USD', 0, 0, ?, '6109100000',"
                        + " 'Cotton', 'Daily wear', now(), now())",
                id, "producto-" + sufijo, "ext-" + sufijo, PESO_UNIDAD_GRAMOS);
        jdbcTemplate.update("INSERT INTO product_translation (id, product_id, language, title, created_at,"
                + " updated_at) VALUES (gen_random_uuid(), ?, 'en', 'Cotton T-shirt', now(), now())", id);
        return id;
    }
}
