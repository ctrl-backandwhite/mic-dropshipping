package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.config.BaseIntegration;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.model.ShippingOption;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.FulfillmentResult;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.ParcelContent;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.TrackingSnapshot;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.TrackingStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Certificación del recorrido COMPLETO de una compra por HTTP: desde que el cliente cotiza el envío
 * hasta que recibe el paquete en su casa.
 *
 * <p><b>Por qué existe.</b> Cada tramo tenía su prueba —el checkout, las transiciones del pedido, la
 * cola de compras al proveedor, el seguimiento— y ninguna recorría el camino entero. Los fallos que
 * cuestan dinero no viven dentro de un tramo sino en las costuras: un canal que se cotiza y se cobra
 * pero no se guarda, una guía internacional que se emite —y se paga— antes de que nadie haya comprado
 * la mercancía, un pedido que se queda en «pagado» mientras el paquete ya viaja. Aquí se recorre el
 * flujo tal y como lo recorren el cliente y el administrador, y se comprueba el DINERO al céntimo en
 * cada punto donde cambia de manos.
 *
 * <p><b>El transportista se sustituye por un doble</b> ({@link FulfillmentProvider}) y esto no es
 * negociable: las credenciales configuradas son de producción y cada guía emitida es real, se paga y
 * arranca el reloj del seguimiento. Lo que se certifica es NUESTRO camino desde que llegan las tarifas
 * y la trazabilidad, nunca la red del proveedor.
 *
 * <p><b>Cómo se consigue que las cuentas sean predecibles.</b> {@code BaseIntegration} vacía TODAS las
 * tablas antes de cada prueba, incluidas las que siembran las migraciones, así que el cálculo queda
 * desnudo y esta clase repone solo lo que necesita: producto en USD (la conversión de divisa es la
 * identidad), sin regla de margen (el precio de venta ES el coste), IVA del destino al 21% y un derecho
 * de aduana de 3,00 USD por partida arancelaria —en USD y no en euros a propósito, para que el importe
 * no dependa de la tasa de cambio del día—.
 */
// El sondeo periódico del transportista se apaga: corre cada 60 s sobre TODOS los pedidos despachados y
// crearía la guía —o avanzaría el estado— por su cuenta en mitad de la prueba, con lo que el resultado
// dependería del reloj. Aquí cada sincronización la provoca el administrador, que es como se quiere medir.
@TestPropertySource(properties = "nexadrop.fulfillment.sync-enabled=false")
class CompraExtremoAExtremoIT extends BaseIntegration {

    /* ---------- Endpoints del recorrido ---------- */

    private static final String COTIZAR = "/api/shipping/quote";
    private static final String CHECKOUT = "/api/me/orders/checkout";
    private static final String DIRECCIONES = "/api/me/addresses";
    private static final String MIS_PEDIDOS = "/api/me/orders/";
    private static final String COMPRAS_ADMIN = "/api/admin/purchases";
    private static final String PEDIDOS_ADMIN = "/api/admin/orders/";

    /* ---------- El escenario, con sus importes calculados a mano ---------- */

    private static final String PAIS = "ES";
    /** Precio de proveedor en USD. Sin regla de margen, el precio de venta ES el coste: 20,00 $. */
    private static final String PRECIO_BASE = "20.0000";
    private static final int UNIDAD_CENTS = 2000;
    private static final int UNIDADES = 2;
    /** 2 × 20,00 $. */
    private static final int SUBTOTAL_CENTS = 4000;

    /** La línea de ropa: la más barata, y la que se cotiza si el cliente no elige nada. */
    private static final String CANAL_BARATO = "FZZXR";
    private static final int PORTE_BARATO = 785;
    /** La línea generalista: más cara y más lenta. Es la que elige el cliente en esta prueba. */
    private static final String CANAL_ELEGIDO = "THPHR";
    private static final int PORTE_ELEGIDO = 900;

    /** Derecho de aduana por partida arancelaria. El pedido lleva una sola partida → se cobra una vez. */
    private static final int ARANCEL_CENTS = 300;
    private static final int IVA_BPS = 2100;
    /** El arancel NO entra en la base del impuesto: 21% de (4.000 + 900) = 1.029. */
    private static final int IVA_CENTS = 1029;
    /** Lo que se cobra por envío = tarifa del canal elegido + arancel: 900 + 300. */
    private static final int ENVIO_COBRADO_CENTS = 1200;
    /** 4.000 + 1.200 + 1.029. */
    private static final int TOTAL_CENTS = 6229;

    private static final long SALDO_INICIAL_CENTS = 100_000L;

    /** Asunto del aviso por paso intermedio del envío, en el idioma del comprador. */
    private static final String AVISO_DE_SEGUIMIENTO = "Actualización de tu envío";

    /* ---------- Lo que devuelve el transportista doblado ---------- */

    private static final String TRANSPORTISTA = "YUNEXPRESS";
    /** Nº de seguimiento que ve el cliente. */
    private static final String GUIA = "YT2600000012345";
    /** Referencia interna de la guía: es por ella por la que se sondea la trazabilidad de cada bulto. */
    private static final String REFERENCIA_GUIA = "YTREF-0001";

    private static final List<ShippingOption> CANALES_COTIZADOS = List.of(
            new ShippingOption(CANAL_BARATO, "Apparel line", PORTE_BARATO, 5, 8),
            new ShippingOption(CANAL_ELEGIDO, "Global line", PORTE_ELEGIDO, 6, 10));

    /**
     * USE_BIG_DECIMAL_FOR_FLOATS: sin él Jackson lee los importes como {@code double} y una comparación
     * «al céntimo» pasaría a depender del binario en coma flotante, que no es lo que se quiere medir.
     */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    /** El transportista, sustituido: ver la nota de la clase. */
    @MockitoBean
    private FulfillmentProvider fulfillment;

    /**
     * Las reglas de margen viven en una caché que se calienta al ARRANCAR el contexto, antes de que el
     * TRUNCATE se lleve las que siembra Liquibase: sin invalidarla, la regla global seguiría marcando el
     * precio de cada línea y los importes esperados dejarían de cuadrar.
     */
    @Autowired
    private MarginService marginService;

    private UUID clienteId;
    private String tokenCliente;
    private String tokenAdmin;
    private UUID productoId;
    private UUID direccionId;

    @BeforeEach
    void prepararEscenario() {
        marginService.invalidateCache();
        clienteId = UUID.randomUUID();
        tokenCliente = jwt.userToken(clienteId, correoDelCliente(), "USER");
        tokenAdmin = jwt.userToken(UUID.randomUUID(), "admin@nx036.local", "ADMIN");
        insertarUsuario(clienteId, correoDelCliente());
        acreditarMonedero(clienteId, SALDO_INICIAL_CENTS);
        productoId = insertarProductoDeclarable();
        insertarIva(PAIS, IVA_BPS);
        insertarReglaDeAduana(PAIS, ARANCEL_CENTS);
        direccionId = crearDireccion();

        // El transportista cotiza dos canales, del más barato al más caro, y `amountUsdCents` es el del
        // más barato: es exactamente lo que devuelve el servicio real.
        when(fulfillment.isSupported(anyString())).thenReturn(true);
        // Cómo se llama y que ya puede emitir la guía. Lo primero es lo que el pedido guarda al cobrar y
        // lo que decide después a quién pedírsela; lo segundo, lo que solo el transportista sabe —no
        // puede hasta tener la mercancía dada de alta—. Un simulacro contesta null y false a las dos
        // cosas, y entonces esta compra se quedaría sin despachar por un motivo ajeno a lo que prueba.
        when(fulfillment.nombre()).thenReturn(TRANSPORTISTA);
        when(fulfillment.readyToShip(any())).thenReturn(true);
        when(fulfillment.quote(anyString(), any())).thenReturn(new ShippingQuote(true, PAIS, PORTE_BARATO,
                "Standard Shipping", "Standard Shipping", 5, 8, "EU", CANALES_COTIZADOS));
        when(fulfillment.createShipments(any()))
                .thenReturn(List.of(new FulfillmentResult(TRANSPORTISTA, GUIA, REFERENCIA_GUIA, 12, 1, 1000,
                        SUBTOTAL_CENTS, CANAL_ELEGIDO, List.of(new ParcelContent(0, UNIDADES)), null)));
        devolverTrazabilidad(registrado());
    }

    /* ==================================================================================
     *  El recorrido completo
     * ================================================================================== */

    @Test
    @DisplayName("del pago a la entrega: se cobra lo cotizado, se compra, se despacha y el cliente lo recibe")
    void elClienteCompraYRecibeSuPedido() {
        // 1 · El cliente cotiza el envío y ve las dos formas disponibles.
        JsonNode previaPorDefecto = cotizar(null);
        assertThat(previaPorDefecto.get("options")).hasSize(2);
        assertThat(previaPorDefecto.get("selectedShippingOptionCode").asText())
                .as("sin elegir nada se cotiza la forma más barata").isEqualTo(CANAL_BARATO);

        // 2 · Elige la forma de envío cara: el desglose se recalcula contra el servidor, porque el
        // impuesto sube con el porte y sumar la diferencia en el navegador dejaría el total corto.
        JsonNode previa = cotizar(CANAL_ELEGIDO);
        assertThat(previa.get("selectedShippingOptionCode").asText()).isEqualTo(CANAL_ELEGIDO);
        assertThat(previa.get("subtotalUsdCents").asInt()).isEqualTo(SUBTOTAL_CENTS);
        assertThat(previa.get("amountUsdCents").asInt())
                .as("el envío cotizado incluye el arancel, que es lo que se va a cobrar")
                .isEqualTo(ENVIO_COBRADO_CENTS);
        assertThat(previa.get("customsHandlingUsdCents").asInt()).isEqualTo(ARANCEL_CENTS);
        assertThat(centimosDeTexto(previa.get("taxFormatted").asText())).isEqualTo(IVA_CENTS);
        assertThat(centimosDeTexto(previa.get("totalFormatted").asText())).isEqualTo(TOTAL_CENTS);

        // 3 · Paga con el monedero. Lo enseñado antes de pagar tiene que ser lo cobrado, al céntimo.
        long saldoAntes = saldoDelCliente();
        JsonNode pedido = pagarConMonedero(CANAL_ELEGIDO);
        UUID pedidoId = UUID.fromString(pedido.get("id").asText());

        assertThat(pedido.get("status").asText()).isEqualTo(OrderStatus.PAID.name());
        assertThat(centimos(pedido, "subtotal")).isEqualTo(SUBTOTAL_CENTS);
        assertThat(centimos(pedido, "shipping"))
                .as("en el PEDIDO el envío es solo el porte: el arancel va en su propia línea, no dentro")
                .isEqualTo(PORTE_ELEGIDO);
        assertThat(centimos(pedido, "customsDuty"))
                .as("y el arancel se devuelve aparte, para que el desglose no tenga que deducirlo")
                .isEqualTo(ARANCEL_CENTS);
        assertThat(centimos(pedido, "tax")).isEqualTo(IVA_CENTS);
        assertThat(centimos(pedido, "total")).isEqualTo(TOTAL_CENTS);
        assertThat(enteroDe("SELECT customs_duty_cents FROM customer_order WHERE id = ?", pedidoId))
                .as("el arancel se guarda aparte para que la factura pueda desglosar lo que se cobró")
                .isEqualTo(ARANCEL_CENTS);
        assertThat(canalGuardado(pedidoId))
                .as("sin la columna, la guía saldría por un canal distinto del cotizado y del cobrado")
                .isEqualTo(CANAL_ELEGIDO);
        assertThat(saldoAntes - saldoDelCliente())
                .as("del monedero sale EXACTAMENTE el total del pedido, ni un céntimo más").isEqualTo(TOTAL_CENTS);
        assertThat(asuntosDeCorreosDelCliente())
                .as("el pago con saldo cobra en el acto: su primer correo ya es la factura")
                .contains("Factura " + pedido.get("orderNumber").asText());

        // 4 · El administrador ve en su cola qué hay que comprarle al proveedor.
        JsonNode cola = colaDeCompras();
        assertThat(cola).hasSize(1);
        JsonNode compra = cola.get(0);
        assertThat(compra.get("orderId").asText()).isEqualTo(pedidoId.toString());
        assertThat(compra.get("status").asText()).isEqualTo("PENDING");
        assertThat(compra.get("items").get(0).get("quantity").asInt()).isEqualTo(UNIDADES);
        UUID compraId = UUID.fromString(compra.get("id").asText());

        // 5 · Registra la compra en 1688. Con toda la mercancía comprada el pedido pasa por su cuenta a
        // «enviado al proveedor», sin que nadie tenga que acordarse de pulsarlo en otra pantalla.
        assertThat(registrarCompra(compraId).get("status").asText()).isEqualTo("PURCHASED");
        assertThat(estadoDelPedido(pedidoId)).isEqualTo(OrderStatus.FORWARDED.name());

        // 6 · Y aun así la guía NO se emite: la mercancía está comprada pero sigue en casa del proveedor.
        sincronizarSeguimiento(pedidoId);
        verify(fulfillment, never()).createShipments(any());
        assertThat(guiaDelPedido(pedidoId))
                .as("emitir aquí la guía es pagarla y arrancar el seguimiento sobre un paquete que no existe").isNull();

        // 7 · El proveedor la despacha y el almacén chino la recibe.
        assertThat(registrarEnvioDelProveedor(compraId).get("status").asText()).isEqualTo("IN_TRANSIT");
        assertThat(registrarRecepcionEnAlmacen(compraId).get("status").asText()).isEqualTo("AT_WAREHOUSE");

        // 8 · El fichero de re-empaquetado se da por descargado y subido al OMS; solo queda cerrar el ciclo.
        darPorExportado(compraId);
        assertThat(confirmarReempaquetado(compraId).get("marked").asInt()).isEqualTo(1);
        assertThat(estadoDeLaCompra(compraId)).isEqualTo("PACKED");

        // 9 · El administrador despacha el pedido. Ya lo estaba desde el paso 5, así que la operación es
        // idempotente: repetir el clic no puede romper nada ni dejar el pedido en otro estado.
        assertThat(despachar(pedidoId)).isEqualTo(200);
        assertThat(estadoDelPedido(pedidoId)).isEqualTo(OrderStatus.FORWARDED.name());

        // 10 · Ahora sí: con los bultos camino del almacén, sincronizar emite la guía internacional.
        sincronizarSeguimiento(pedidoId);
        verify(fulfillment).createShipments(any());
        assertThat(guiaDelPedido(pedidoId)).isEqualTo(GUIA);
        assertThat(estadoDelPedido(pedidoId))
                .as("registrar la guía no adelanta el estado: el paquete todavía no lo ha recogido nadie")
                .isEqualTo(OrderStatus.FORWARDED.name());

        // 11 · Llega la trazabilidad: el transportista recoge y el pedido pasa a «en camino». El paso
        // intermedio que viene en la misma tanda —la llegada al país de destino— tiene aviso propio: no lo
        // cubre ningún cambio de estado del pedido y es justo lo que el comprador espera que le cuenten.
        devolverTrazabilidad(enCamino());
        sincronizarSeguimiento(pedidoId);
        assertThat(estadoDelPedido(pedidoId)).isEqualTo(OrderStatus.SHIPPED.name());
        assertThat(asuntosDeCorreosDelCliente()).contains("Tu pedido va en camino");
        assertThat(avisosDeSeguimiento()).as("la llegada al país de destino es novedad para el comprador y se le avisa")
                .isEqualTo(1);

        // 12 · Y se entrega, con un paso intermedio más («En reparto») por el camino.
        devolverTrazabilidad(entregado());
        sincronizarSeguimiento(pedidoId);
        assertThat(estadoDelPedido(pedidoId)).isEqualTo(OrderStatus.DELIVERED.name());
        assertThat(asuntosDeCorreosDelCliente()).contains("Tu pedido ha sido entregado");
        assertThat(avisosDeSeguimiento()).as("un aviso por paso intermedio nuevo, y ni uno repetido de los ya avisados")
                .isEqualTo(2);

        // 13 · Sondear otra vez lo mismo no puede volver a contarlo: el transportista reenvía sus eventos
        // en cada consulta, y avisar dos veces del mismo paso es el motivo por el que el comprador acaba
        // marcando los correos como spam.
        int correosAlEntregar = asuntosDeCorreosDelCliente().size();
        sincronizarSeguimiento(pedidoId);
        assertThat(asuntosDeCorreosDelCliente()).hasSize(correosAlEntregar);
        assertThat(descripcionesDe(miSeguimiento(pedidoId))).as("ni un paso repetido en el timeline")
                .doesNotHaveDuplicates();

        // 14 · La guía quedó archivada con el canal por el que se cotizó y se cobró.
        assertThat(jdbcTemplate.queryForObject("SELECT product_code FROM order_shipment WHERE order_id = ?",
                String.class, pedidoId)).isEqualTo(CANAL_ELEGIDO);

        // 15 · Lo que el cliente ve al final en su ficha y en su seguimiento.
        JsonNode fichaFinal = miPedido(pedidoId);
        assertThat(fichaFinal.get("status").asText()).isEqualTo(OrderStatus.DELIVERED.name());
        assertThat(fichaFinal.get("trackingNumber").asText()).isEqualTo(GUIA);
        assertThat(fichaFinal.get("trackingCarrier").asText()).isEqualTo(TRANSPORTISTA);
        assertThat(centimos(fichaFinal, "total"))
                .as("el total de la ficha sigue siendo el que se cobró el día de la compra").isEqualTo(TOTAL_CENTS);

        JsonNode seguimiento = miSeguimiento(pedidoId);
        assertThat(seguimiento.get("status").asText()).isEqualTo(OrderStatus.DELIVERED.name());
        assertThat(seguimiento.get("trackingNumber").asText()).isEqualTo(GUIA);
        assertThat(descripcionesDe(seguimiento)).as("el cliente ve el camino entero, no solo el desenlace")
                .contains(PASO_ALTA, PASO_RECOGIDA, PASO_LLEGADA, PASO_REPARTO, PASO_ENTREGA);
    }

    @Test
    @DisplayName("la guía internacional no se emite mientras quede mercancía sin comprar o sin salir")
    void laGuiaEsperaAQueLaMercanciaVayaCaminoDelAlmacen() {
        UUID pedidoId = UUID.fromString(pagarConMonedero(CANAL_ELEGIDO).get("id").asText());
        UUID compraId = UUID.fromString(colaDeCompras().get(0).get("id").asText());

        // Despachado a mano por el administrador con la compra todavía sin hacer: es el punto en el que
        // el sistema podía emitir —y pagar— una guía para mercancía que nadie ha comprado.
        assertThat(despachar(pedidoId)).isEqualTo(200);
        assertThat(estadoDeLaCompra(compraId)).isEqualTo("PENDING");
        sincronizarSeguimiento(pedidoId);
        verify(fulfillment, never()).createShipments(any());
        assertThat(guiaDelPedido(pedidoId)).isNull();

        // Comprada, pero aún en casa del proveedor: sigue sin haber nada que enviar.
        registrarCompra(compraId);
        assertThat(estadoDeLaCompra(compraId)).isEqualTo("PURCHASED");
        sincronizarSeguimiento(pedidoId);
        verify(fulfillment, never()).createShipments(any());
        assertThat(guiaDelPedido(pedidoId)).isNull();

        // En cuanto el bulto va camino del almacén, la guía se emite.
        registrarEnvioDelProveedor(compraId);
        assertThat(estadoDeLaCompra(compraId)).isEqualTo("IN_TRANSIT");
        sincronizarSeguimiento(pedidoId);
        verify(fulfillment).createShipments(any());
        assertThat(guiaDelPedido(pedidoId)).isEqualTo(GUIA);
    }

    /* ==================================================================================
     *  La trazabilidad que devuelve el doble del transportista, por fases
     * ================================================================================== */

    /**
     * Los textos de los pasos son los del transportista, en su idioma y con sus palabras
     * ({@code YunExpressTrackNode}), no traducciones nuestras: el timeline guarda lo que llega y mezclarlo
     * con nuestros propios literales es lo que haría pasar por «repetido» algo que no lo es —o al revés—.
     */
    private static final String PASO_ALTA = "Order created";
    private static final String PASO_RECOGIDA = "Collected by carrier";
    private static final String PASO_LLEGADA = "Arrive at the destination country";
    private static final String PASO_REPARTO = "The parcel is currently being attempted for delivery.";
    private static final String PASO_ENTREGA = "Delivered";

    /** Guía dada de alta: el paquete existe en el sistema del transportista pero no se ha movido. */
    private static TrackingSnapshot registrado() {
        return new TrackingSnapshot(OrderStatus.FORWARDED,
                List.of(paso(OrderStatus.FORWARDED, PASO_ALTA, "Dongguan, CN", 0)));
    }

    /** El transportista ya lo lleva encima y el paquete ha llegado al país de destino. */
    private static TrackingSnapshot enCamino() {
        return new TrackingSnapshot(OrderStatus.SHIPPED,
                List.of(paso(OrderStatus.FORWARDED, PASO_ALTA, "Dongguan, CN", 0),
                        paso(OrderStatus.SHIPPED, PASO_RECOGIDA, "Dongguan, CN", 2),
                        paso(OrderStatus.SHIPPED, PASO_LLEGADA, "Madrid, ES", 6)));
    }

    /** Entregado al destinatario, con el intento de reparto por el camino. */
    private static TrackingSnapshot entregado() {
        return new TrackingSnapshot(OrderStatus.DELIVERED,
                List.of(paso(OrderStatus.FORWARDED, PASO_ALTA, "Dongguan, CN", 0),
                        paso(OrderStatus.SHIPPED, PASO_RECOGIDA, "Dongguan, CN", 2),
                        paso(OrderStatus.SHIPPED, PASO_LLEGADA, "Madrid, ES", 6),
                        paso(OrderStatus.SHIPPED, PASO_REPARTO, "Madrid, ES", 8),
                        paso(OrderStatus.DELIVERED, PASO_ENTREGA, "Madrid, ES", 9)));
    }

    /** Un paso ocurrido hace {@code hace} horas, para que el timeline salga en orden cronológico. */
    private static TrackingStep paso(OrderStatus estado, String descripcion, String lugar, int hace) {
        return new TrackingStep(estado, descripcion, lugar, Instant.now().minus(24L - hace, ChronoUnit.HOURS));
    }

    private void devolverTrazabilidad(TrackingSnapshot instantanea) {
        when(fulfillment.track(anyString(), any(), anyString())).thenReturn(instantanea);
    }

    /* ==================================================================================
     *  Peticiones del cliente
     * ================================================================================== */

    private JsonNode cotizar(String canal) {
        String cuerpo = """
                {"country":"%s","region":null,"couponCode":null,"shippingOptionCode":%s,
                 "items":[{"productId":"%s","quantity":%d}]}
                """.formatted(PAIS, canal == null ? "null" : "\"" + canal + "\"", productoId, UNIDADES);
        return cuerpo(client.post().uri(COTIZAR).header(HttpHeaders.AUTHORIZATION, bearer(tokenCliente))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(cuerpo).exchange().expectStatus().isOk());
    }

    private JsonNode pagarConMonedero(String canal) {
        String cuerpo = """
                {"shippingAddressId":"%s","paymentMethod":"WALLET","shippingOptionCode":"%s",
                 "items":[{"productId":"%s","quantity":%d}]}
                """.formatted(direccionId, canal, productoId, UNIDADES);
        return cuerpo(client.post().uri(CHECKOUT).header(HttpHeaders.AUTHORIZATION, bearer(tokenCliente))
                // La clave de idempotencia es OBLIGATORIA en todo lo que mueve dinero: sin ella el
                // servidor responde 400. Un arnés de prueba es un cliente más y tiene que mandarla.
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(cuerpo).exchange().expectStatus().isCreated());
    }

    private JsonNode miPedido(UUID pedidoId) {
        return cuerpo(client.get().uri(MIS_PEDIDOS + pedidoId).header(HttpHeaders.AUTHORIZATION, bearer(tokenCliente))
                .exchange().expectStatus().isOk());
    }

    private JsonNode miSeguimiento(UUID pedidoId) {
        return cuerpo(client.get().uri(MIS_PEDIDOS + pedidoId + "/tracking")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenCliente)).exchange().expectStatus().isOk());
    }

    /* ==================================================================================
     *  Peticiones del administrador
     * ================================================================================== */

    private JsonNode colaDeCompras() {
        return cuerpo(client.get().uri(COMPRAS_ADMIN).header(HttpHeaders.AUTHORIZATION, bearer(tokenAdmin)).exchange()
                .expectStatus().isOk());
    }

    private JsonNode registrarCompra(UUID compraId) {
        return postAdmin(COMPRAS_ADMIN + "/" + compraId + "/bought",
                "{\"purchaseRef\":\"1688-000123\",\"costCny\":\"88.00\",\"shippingCny\":\"6.00\"}");
    }

    private JsonNode registrarEnvioDelProveedor(UUID compraId) {
        return postAdmin(COMPRAS_ADMIN + "/" + compraId + "/shipped",
                "{\"domesticTracking\":\"SF1234567890\",\"domesticCarrier\":\"SF\"}");
    }

    private JsonNode registrarRecepcionEnAlmacen(UUID compraId) {
        return postAdmin(COMPRAS_ADMIN + "/" + compraId + "/received", null);
    }

    private JsonNode confirmarReempaquetado(UUID compraId) {
        return postAdmin(COMPRAS_ADMIN + "/pack-sheet/confirm", "[\"" + compraId + "\"]");
    }

    private int despachar(UUID pedidoId) {
        return client.post().uri(PEDIDOS_ADMIN + pedidoId + "/forward")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenAdmin)).exchange().returnResult(Void.class).getStatus()
                .value();
    }

    private void sincronizarSeguimiento(UUID pedidoId) {
        client.post().uri(PEDIDOS_ADMIN + pedidoId + "/sync-tracking")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenAdmin)).exchange().expectStatus().isOk();
    }

    private JsonNode postAdmin(String uri, String cuerpoJson) {
        WebTestClient.RequestBodySpec peticion = client.post().uri(uri).header(HttpHeaders.AUTHORIZATION,
                bearer(tokenAdmin));
        WebTestClient.ResponseSpec respuesta = cuerpoJson == null
                ? peticion.exchange()
                : peticion.contentType(MediaType.APPLICATION_JSON).bodyValue(cuerpoJson).exchange();
        return cuerpo(respuesta.expectStatus().isOk());
    }

    /* ==================================================================================
     *  Lecturas de la base
     * ================================================================================== */

    private long saldoDelCliente() {
        Long saldo = jdbcTemplate.queryForObject("SELECT balance_usd_cents FROM wallet WHERE user_id = ?", Long.class,
                clienteId);
        return saldo == null ? 0L : saldo;
    }

    private String estadoDelPedido(UUID pedidoId) {
        return jdbcTemplate.queryForObject("SELECT status FROM customer_order WHERE id = ?", String.class, pedidoId);
    }

    private String guiaDelPedido(UUID pedidoId) {
        return jdbcTemplate.queryForObject("SELECT tracking_number FROM customer_order WHERE id = ?", String.class,
                pedidoId);
    }

    private String canalGuardado(UUID pedidoId) {
        return jdbcTemplate.queryForObject("SELECT shipping_channel_code FROM customer_order WHERE id = ?",
                String.class, pedidoId);
    }

    private String estadoDeLaCompra(UUID compraId) {
        return jdbcTemplate.queryForObject("SELECT status FROM supplier_purchase WHERE id = ?", String.class, compraId);
    }

    private int enteroDe(String sql, UUID id) {
        Integer valor = jdbcTemplate.queryForObject(sql, Integer.class, id);
        return valor == null ? 0 : valor;
    }

    /** Asuntos de los correos encolados para el cliente, en orden. */
    private List<String> asuntosDeCorreosDelCliente() {
        return jdbcTemplate.queryForList(
                "SELECT subject FROM outbound_email WHERE to_address = ?" + " ORDER BY created_at ASC", String.class,
                correoDelCliente());
    }

    /** Cuántos avisos de paso intermedio del envío («Actualización de tu envío») ha recibido el cliente. */
    private int avisosDeSeguimiento() {
        return (int) asuntosDeCorreosDelCliente().stream().filter(AVISO_DE_SEGUIMIENTO::equals).count();
    }

    /**
     * El fichero de re-empaquetado se da por descargado y subido al OMS.
     *
     * <p>Marcar la compra como exportada es lo único que ese paso deja en el sistema, y sin ello la
     * confirmación se rechaza: el número de la orden de re-empaquetado lo devuelve el OMS al importar el
     * fichero, así que no puede existir antes. Generar el .xls no aporta nada a lo que aquí se certifica y
     * tiene su propia prueba.
     */
    private void darPorExportado(UUID compraId) {
        jdbcTemplate.update("UPDATE supplier_purchase SET exported_at = now() WHERE id = ?", compraId);
    }

    /* ==================================================================================
     *  Siembra del escenario
     * ================================================================================== */

    private String correoDelCliente() {
        return "compra-" + clienteId + "@nx036.local";
    }

    private void insertarUsuario(UUID id, String email) {
        jdbcTemplate.update("INSERT INTO users (id, email, role, active, language, created_at, updated_at)"
                + " VALUES (?, ?, 'USER', true, 'es', now(), now())", id, email);
    }

    private void acreditarMonedero(UUID id, long centimos) {
        jdbcTemplate.update("INSERT INTO wallet (id, user_id, balance_usd_cents, hold_usd_cents,"
                + " currency_default, status, created_at, updated_at)"
                + " VALUES (gen_random_uuid(), ?, ?, 0, 'USD', 'ACTIVE', now(), now())", id, centimos);
    }

    /**
     * Producto con TODO lo que la aduana exige, porque sin ello el despacho se corta antes de empezar:
     * nombre en inglés, nombre en chino con ideogramas de verdad, partida arancelaria, peso y precio.
     * Lleva proveedor porque es lo que agrupa la compra en 1688.
     */
    private UUID insertarProductoDeclarable() {
        UUID proveedorId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO supplier (id, external_id, source, name, created_at, updated_at)"
                        + " VALUES (?, ?, '1688', 'Proveedor de prueba', now(), now())",
                proveedorId, "sup-" + proveedorId);

        UUID id = UUID.randomUUID();
        String sufijo = id.toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO product (id, slug, external_id, source, supplier_id, title_zh,"
                + " status, moq, base_price, currency, hs_code, weight_grams, created_at, updated_at)"
                + " VALUES (?, ?, ?, '1688', ?, '棉质T恤', 'ACTIVE', 1, ?::numeric, 'USD', '610910', 500,"
                + " now(), now())", id, "producto-" + sufijo, "ext-" + sufijo, proveedorId, PRECIO_BASE);

        insertarTraduccion(id, "es", "Camiseta de algodón");
        insertarTraduccion(id, "en", "Cotton T-Shirt");
        insertarTraduccion(id, "zh", "棉质T恤");
        return id;
    }

    private void insertarTraduccion(UUID productoId, String idioma, String titulo) {
        jdbcTemplate.update("INSERT INTO product_translation (id, product_id, language, title, created_at,"
                + " updated_at) VALUES (gen_random_uuid(), ?, ?, ?, now(), now())", productoId, idioma, titulo);
    }

    private void insertarIva(String pais, int rateBps) {
        jdbcTemplate.update(
                "INSERT INTO country_tax_rate (id, country_code, label, rate_bps, active,"
                        + " created_at, updated_at) VALUES (gen_random_uuid(), ?, 'IVA', ?, true, now(), now())",
                pais, rateBps);
    }

    /**
     * Regla de aduana del destino con el derecho por partida en USD.
     *
     * <p>El importe real es de 3 EUR y depende de la tasa del día; aquí se siembra su equivalente en
     * dólares para que el número esperado no cambie con el cambio. La franquicia se deja a cero —«sin
     * configurar»— porque lo que se certifica es el recorrido de la compra, no el umbral de importación.
     */
    private void insertarReglaDeAduana(String pais, int derechoUsdCents) {
        jdbcTemplate.update("INSERT INTO country_customs_rule (id, country_code, tax_mode, de_minimis_amount,"
                + " de_minimis_currency, over_threshold_policy, per_article_fee_amount,"
                + " per_article_fee_currency, active, created_at, updated_at)"
                + " VALUES (gen_random_uuid(), ?, 'DDP', 0, 'USD', 'SURCHARGE', ?::numeric, 'USD', true,"
                + " now(), now())", pais, BigDecimal.valueOf(derechoUsdCents, 2).toPlainString());
    }

    private UUID crearDireccion() {
        String cuerpo = """
                {"fullName":"Comprador de prueba","phone":"+34600000000","line1":"Gran Via 1",
                 "city":"Madrid","state":"M","postalCode":"28013","country":"%s","isDefault":true}
                """.formatted(PAIS);
        JsonNode creada = cuerpo(client.post().uri(DIRECCIONES).header(HttpHeaders.AUTHORIZATION, bearer(tokenCliente))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(cuerpo).exchange().expectStatus().isCreated());
        return UUID.fromString(creada.get("id").asText());
    }

    /* ==================================================================================
     *  Utilidades
     * ================================================================================== */

    private JsonNode cuerpo(WebTestClient.ResponseSpec respuesta) {
        String texto = respuesta.expectBody(String.class).returnResult().getResponseBody();
        try {
            return JSON.readTree(texto == null ? "{}" : texto);
        } catch (JacksonException e) {
            throw new IllegalStateException("Respuesta no es JSON: " + texto, e);
        }
    }

    private static int centimos(JsonNode nodo, String campo) {
        JsonNode valor = nodo.get(campo);
        assertThat(valor).as("falta el campo monetario «%s» en la respuesta: %s", campo, nodo).isNotNull();
        return valor.decimalValue().movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    /** Céntimos de un importe ya formateado por el backend ("$62.29"). */
    private static int centimosDeTexto(String formateado) {
        String limpio = formateado.replace(",", "").replaceAll("[^0-9.\\-]", "");
        return new BigDecimal(limpio).movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    /** Las descripciones del timeline de seguimiento, en el orden en que las ve el cliente. */
    private static List<String> descripcionesDe(JsonNode seguimiento) {
        List<String> pasos = new ArrayList<>();
        seguimiento.get("events").forEach(evento -> pasos.add(evento.get("description").asText()));
        return pasos;
    }
}
