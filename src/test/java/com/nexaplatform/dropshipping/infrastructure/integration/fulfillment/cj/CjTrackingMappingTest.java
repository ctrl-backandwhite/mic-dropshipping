package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.cj;

import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.enums.ShipmentEventMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Lectura del seguimiento de CJ y traducción de su estado al estado del pedido.
 *
 * <p>La respuesta base es la <b>literal de la documentación</b> de CJ v2.0
 * ({@code GET /api2.0/v1/logistic/trackInfo?trackNumber=…}), consultada el 18-ago-2026.
 *
 * <p>Aquí se fijan las dos decisiones que, si alguien las cambia sin darse cuenta, se notan tarde y en
 * el pedido del cliente:
 *
 * <ul>
 *   <li><b>Un estado que no conocemos no avanza el pedido.</b> CJ puede añadir estados cuando quiera;
 *       tratar lo desconocido como «en tránsito» —o peor, como «entregado»— cerraría pedidos que siguen
 *       de camino. Se anota en el registro con el texto exacto y se ignora.</li>
 *   <li><b>{@code deliveryTime} viene SIN zona horaria</b> ({@code "2021-06-17 07:04:04"}). Se
 *       interpreta como hora de Pekín, que es la que usa el panel de CJ. Si se leyera como UTC, los
 *       hitos aparecerían <b>8 horas en el futuro</b>: el cliente vería «entregado» con fecha de mañana.</li>
 * </ul>
 */
class CjTrackingMappingTest {

    /** Respuesta literal del ejemplo de la documentación de CJ. */
    private static final String RESPUESTA_REAL = """
            {"code":200,"result":true,"message":"Success","data":[
              {"trackingNumber":"CJPKL7160102171YQ","logisticName":"CJPacket Sensitive","trackingFrom":"CN",
               "trackingTo":"US","deliveryDay":"13","deliveryTime":"2021-06-17 07:04:04",
               "trackingStatus":"In transit","lastMileCarrier":"CJPacket","lastTrackNumber":"926112903032124"}]}
            """;

    // ─────────────────────────────────────────────────────── la respuesta real

    @Test
    @DisplayName("la respuesta de la documentación se lee entera: número, estado, última milla y fecha")
    void leeLaRespuestaReal() {
        List<CjTrackReader.Seguimiento> seguimientos = CjTrackReader.leer(RESPUESTA_REAL);

        assertThat(seguimientos).hasSize(1);
        CjTrackReader.Seguimiento seguimiento = seguimientos.get(0);
        assertThat(seguimiento.numeroSeguimiento()).isEqualTo("CJPKL7160102171YQ");
        assertThat(seguimiento.nombreLogistico()).isEqualTo("CJPacket Sensitive");
        assertThat(seguimiento.estado()).isEqualTo(CjTrackStatus.EN_TRANSITO);
        assertThat(seguimiento.transportistaUltimaMilla()).isEqualTo("CJPacket");
        assertThat(seguimiento.numeroUltimaMilla())
                .as("es el número del transportista local, con el que el cliente consulta en su país")
                .isEqualTo("926112903032124");
    }

    @Test
    @DisplayName("«In transit» deja el pedido en ENVIADO, no en entregado")
    void enTransitoEsEnviado() {
        assertThat(CjTrackReader.leer(RESPUESTA_REAL).get(0).estado().estadoPedido())
                .isEqualTo(OrderStatus.SHIPPED);
    }

    // ─────────────────────────────────────────────────────── la fecha sin zona

    @Test
    @DisplayName("deliveryTime se interpreta como hora de Pekín: 07:04 en China son las 23:04 UTC del día anterior")
    void laFechaSeInterpretaComoHoraDePekin() {
        Instant momento = CjTrackReader.leer(RESPUESTA_REAL).get(0).momento();

        assertThat(momento)
                .as("CJ no manda zona; leerlo como UTC pondría el hito 8 horas en el futuro")
                .isEqualTo(Instant.parse("2021-06-16T23:04:04Z"));
    }

    @ParameterizedTest(name = "deliveryTime {0} → se usa la hora de ahora")
    @DisplayName("una fecha ausente o ilegible no deja el hito sin fecha: se usa la de ahora")
    @ValueSource(strings = { "", "17/06/2021", "2021-06-17T07:04:04Z" })
    void fechaIlegibleCaeEnAhora(String deliveryTime) {
        String cuerpo = """
                {"code":200,"result":true,"message":"Success","data":[
                  {"trackingNumber":"CJPKL7160102171YQ","deliveryTime":"%s","trackingStatus":"Delivered"}]}
                """.formatted(deliveryTime);

        Instant momento = CjTrackReader.leer(cuerpo).get(0).momento();

        // Un null aquí revienta el orden del timeline: FulfillmentService ordena los pasos con
        // Comparator.comparing(TrackingStep::occurredAt), que no admite nulos.
        assertThat(momento).isNotNull()
                .isAfter(Instant.now().minusSeconds(120))
                .isBefore(Instant.now().plusSeconds(120));
    }

    // ─────────────────────────────────────────────────────── estados desconocidos

    @Test
    @DisplayName("un estado que CJ no tenía documentado no avanza el pedido: se ignora el hito")
    void elEstadoDesconocidoNoAvanzaElPedido() {
        String cuerpo = """
                {"code":200,"result":true,"message":"Success","data":[
                  {"trackingNumber":"CJPKL7160102171YQ","deliveryTime":"2021-06-17 07:04:04",
                   "trackingStatus":"Waiting for the moon"}]}
                """;

        assertThat(CjTrackReader.leer(cuerpo))
                .as("tratarlo como «en tránsito» o «entregado» cerraría pedidos que siguen de camino")
                .isEmpty();
    }

    @Test
    @DisplayName("un número que CJ no reconoce llega sin estado y se ignora")
    void elNumeroDesconocidoSeIgnora() {
        String cuerpo = """
                {"code":200,"result":true,"message":"Success","data":[
                  {"trackingNumber":"NOEXISTE123","logisticName":"","trackingStatus":""}]}
                """;

        assertThat(CjTrackReader.leer(cuerpo)).isEmpty();
    }

    @Test
    @DisplayName("un hito sin número de seguimiento se ignora: no se sabría a qué bulto pertenece")
    void elHitoSinNumeroSeIgnora() {
        String cuerpo = """
                {"code":200,"result":true,"message":"Success","data":[
                  {"trackingNumber":"","trackingStatus":"Delivered"}]}
                """;

        assertThat(CjTrackReader.leer(cuerpo)).isEmpty();
    }

    // ─────────────────────────────────────────────────────── respuestas que no traen datos

    @Test
    @DisplayName("una respuesta de error de CJ devuelve lista vacía y no revienta el sondeo")
    void laRespuestaDeErrorDevuelveVacio() {
        String cuerpo = """
                {"code":1600200,"result":false,"message":"trackNumber is required","data":null}
                """;

        assertThatCode(() -> assertThat(CjTrackReader.leer(cuerpo)).isEmpty()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("code 200 con result false tampoco vale: CJ contesta «bien» sin haber consultado nada")
    void resultFalseTambienEsVacio() {
        String cuerpo = """
                {"code":200,"result":false,"message":"Fail","data":[
                  {"trackingNumber":"CJPKL7160102171YQ","trackingStatus":"Delivered"}]}
                """;

        assertThat(CjTrackReader.leer(cuerpo)).isEmpty();
    }

    @Test
    @DisplayName("una consulta sin resultados devuelve lista vacía")
    void laListaVaciaDevuelveVacio() {
        assertThat(CjTrackReader.leer("""
                {"code":200,"result":true,"message":"Success","data":[]}
                """)).isEmpty();
    }

    @Test
    @DisplayName("un cuerpo ilegible no tumba el sondeo del pedido")
    void elCuerpoIlegibleNoRevienta() {
        assertThatCode(() -> assertThat(CjTrackReader.leer("<html>502 Bad Gateway</html>")).isEmpty())
                .doesNotThrowAnyException();
        assertThatCode(() -> assertThat(CjTrackReader.leer(null)).isEmpty()).doesNotThrowAnyException();
    }

    // ─────────────────────────────────────────────────────── consulta en lote

    @Test
    @DisplayName("varios números en una sola consulta: cada bulto conserva su número y su estado")
    void laConsultaEnLoteDevuelveCadaBulto() {
        String cuerpo = """
                {"code":200,"result":true,"message":"Success","data":[
                  {"trackingNumber":"CJPKL0000000001YQ","deliveryTime":"2021-06-17 07:04:04",
                   "trackingStatus":"Delivered","lastMileCarrier":"USPS","lastTrackNumber":"111"},
                  {"trackingNumber":"CJPKL0000000002YQ","deliveryTime":"2021-06-18 09:00:00",
                   "trackingStatus":"Out for delivery","lastMileCarrier":"USPS","lastTrackNumber":"222"},
                  {"trackingNumber":"CJPKL0000000003YQ","deliveryTime":"2021-06-18 10:00:00",
                   "trackingStatus":"Created","lastMileCarrier":"","lastTrackNumber":""}]}
                """;

        List<CjTrackReader.Seguimiento> seguimientos = CjTrackReader.leer(cuerpo);

        assertThat(seguimientos).extracting(CjTrackReader.Seguimiento::numeroSeguimiento)
                .containsExactly("CJPKL0000000001YQ", "CJPKL0000000002YQ", "CJPKL0000000003YQ");
        assertThat(seguimientos).extracting(seguimiento -> seguimiento.estado().estadoPedido())
                .as("el pedido va tan atrasado como su bulto más atrasado: eso lo decide quien agrega")
                .containsExactly(OrderStatus.DELIVERED, OrderStatus.SHIPPED, OrderStatus.FORWARDED);
    }

    @Test
    @DisplayName("en el lote, un bulto con estado desconocido se cae y los demás siguen leyéndose")
    void elLoteSobreviveAUnEstadoDesconocido() {
        String cuerpo = """
                {"code":200,"result":true,"message":"Success","data":[
                  {"trackingNumber":"CJPKL0000000001YQ","trackingStatus":"Teleported"},
                  {"trackingNumber":"CJPKL0000000002YQ","trackingStatus":"Delivered"}]}
                """;

        assertThat(CjTrackReader.leer(cuerpo)).extracting(CjTrackReader.Seguimiento::numeroSeguimiento)
                .containsExactly("CJPKL0000000002YQ");
    }

    // ─────────────────────────────────────────────────────── la tabla de estados

    @ParameterizedTest(name = "«{0}» → {1}")
    @DisplayName("cada estado de CJ se traduce a un estado del pedido")
    @CsvSource({
            "Created,                 FORWARDED",
            "Picked up,               SHIPPED",
            "In transit,              SHIPPED",
            "Arrived at destination,  SHIPPED",
            "Out for delivery,        SHIPPED",
            "Delivered,               DELIVERED",
            // Una incidencia no retrocede el pedido: el paquete sigue en circulación y el histórico ya
            // enseña lo que pasó. Es el mismo criterio que con los nodos de YunExpress.
            "Exception,               SHIPPED" })
    void cadaEstadoDeCjTieneSuEstadoDePedido(String textoCj, OrderStatus esperado) {
        assertThat(CjTrackStatus.desde(textoCj)).isNotNull();
        assertThat(CjTrackStatus.desde(textoCj).estadoPedido()).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "«{0}» se reconoce igual")
    @DisplayName("el texto de CJ se reconoce sin importar mayúsculas ni espacios de sobra")
    @ValueSource(strings = { "Delivered", "delivered", "DELIVERED", "  Delivered  " })
    void elTextoSeReconoceSinImportarLaCaja(String textoCj) {
        assertThat(CjTrackStatus.desde(textoCj)).isEqualTo(CjTrackStatus.ENTREGADO);
    }

    @ParameterizedTest(name = "«{0}» no se reconoce")
    @DisplayName("lo que no está en la tabla no se adivina: devuelve null y lo decide quien llama")
    @ValueSource(strings = { "Devuelto", "Returned to sender", "In Transit to somewhere", "?" })
    void loQueNoEstaEnLaTablaNoSeAdivina(String textoCj) {
        assertThat(CjTrackStatus.desde(textoCj)).isNull();
    }

    @Test
    @DisplayName("un texto vacío o nulo no se reconoce")
    void elTextoVacioNoSeReconoce() {
        assertThat(CjTrackStatus.desde(null)).isNull();
        assertThat(CjTrackStatus.desde("   ")).isNull();
    }

    // ─────────────────────────────────────────────────────── lo que ve el cliente

    @Test
    @DisplayName("el hito se guarda con el mensaje ya traducido, no con el texto inglés de CJ")
    void elHitoLlevaMensajeTraducido() {
        assertThat(CjTrackStatus.ENTREGADO.mensaje()).isEqualTo(ShipmentEventMessage.DELIVERED);
        assertThat(CjTrackStatus.EN_TRANSITO.mensaje()).isEqualTo(ShipmentEventMessage.IN_TRANSIT);
        assertThat(CjTrackStatus.EN_REPARTO.mensaje()).isEqualTo(ShipmentEventMessage.OUT_FOR_DELIVERY);
        assertThat(CjTrackStatus.LLEGADA_DESTINO.mensaje()).isEqualTo(ShipmentEventMessage.ARRIVED_COUNTRY);
        assertThat(CjTrackStatus.RECOGIDO.mensaje()).isEqualTo(ShipmentEventMessage.PICKED_UP);
        assertThat(CjTrackStatus.CREADO.mensaje()).isEqualTo(ShipmentEventMessage.REGISTERED);
    }

    @Test
    @DisplayName("la descripción del hito va en español, que es como la deduplica y la traduce el timeline")
    void laDescripcionVaEnEspaniol() {
        // FulfillmentService.collectNewSteps deduplica por «estado|descripción» y OrderEmailService
        // traduce buscando el texto ESPAÑOL: una descripción en inglés se repetiría en cada sondeo y
        // saldría sin traducir en el correo.
        assertThat(CjTrackStatus.ENTREGADO.descripcion()).isEqualTo("Entregado al destinatario");
        assertThat(CjTrackStatus.EN_TRANSITO.descripcion()).isEqualTo("En tránsito internacional");
    }

    @Test
    @DisplayName("una incidencia no tiene mensaje para el cliente: no se le inventa uno")
    void laIncidenciaNoTieneMensajeParaElCliente() {
        assertThat(CjTrackStatus.INCIDENCIA.mensaje())
                .as("no hay texto traducido para «Exception»; enseñar «en tránsito» sería mentirle")
                .isNull();
        assertThat(CjTrackStatus.INCIDENCIA.descripcion()).isNull();
        assertThat(CjTrackStatus.INCIDENCIA.estadoPedido()).isEqualTo(OrderStatus.SHIPPED);
    }
}
