package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Serialización de los cuerpos que se envían a YunExpress.
 *
 * <p>Estos payloads se construían como mapas: un nombre de campo mal escrito no fallaba hasta que el
 * transportista rechazaba la guía. Aquí se fija el JSON exacto que la API acepta —el mismo con el que se
 * creó la guía real YT2621101299000001 contra el sandbox— para que un cambio de nombres se detecte aquí.
 */
class YunExpressRequestsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void elAltaDeEnvioSerializaLosNombresQueEsperaLaApi() throws IOException {
        YunExpressRequests.CreateShipment req = new YunExpressRequests.CreateShipment(
                "BPA", "NX-TEST-0001", "KG", "CM", "W", "PDF",
                List.of(new YunExpressRequests.Parcel(new BigDecimal("0.500"), new BigDecimal("32.0"),
                        new BigDecimal("24.0"), new BigDecimal("5.0"))),
                new YunExpressRequests.Receiver("Ana", "Lopez", "ES", "Zaragoza", "Zaragoza",
                        List.of("Calle Mayor 1"), "50001", "+34600000000", "test@example.com"),
                List.of(new YunExpressRequests.DeclarationLine("Cotton T-shirt", "棉T恤", 1,
                        new BigDecimal("12.5"), new BigDecimal("0.3"), "USD", "6109100000",
                        "Cotton", "Daily wear", "https://example.com/p/1", "SKU-1")),
                null,
                List.of(new YunExpressRequests.ExtraService("V1", "云途预缴")));

        String json = mapper.writeValueAsString(req);

        assertThat(json)
                .contains("\"product_code\":\"BPA\"")
                .contains("\"customer_order_number\":\"NX-TEST-0001\"")
                .contains("\"weight_unit\":\"KG\"")
                .contains("\"size_unit\":\"CM\"")
                .contains("\"label_type\":\"PDF\"")
                .contains("\"country_code\":\"ES\"")
                .contains("\"address_lines\":[\"Calle Mayor 1\"]")
                .contains("\"postal_code\":\"50001\"")
                .contains("\"name_local\":\"棉T恤\"")
                .contains("\"hs_code\":\"6109100000\"")
                .contains("\"sku_code\":\"SKU-1\"");
    }

    @Test
    void sinIossNoSeEnviaElBloqueAduanero() throws IOException {
        // Por encima del umbral de minimis el régimen IOSS no aplica: mandarlo vacío hace que la aduana
        // rechace la liquidación, así que el campo no debe aparecer en el JSON.
        YunExpressRequests.CreateShipment req = new YunExpressRequests.CreateShipment(
                "BPA", "NX-1", "KG", "CM", "W", "PDF", List.of(), null, List.of(), null, null);

        assertThat(mapper.writeValueAsString(req)).doesNotContain("customs_number");
    }

    @Test
    void elPrepagoDeIvaViajaConLosNombresQueEsperaLaApi() throws IOException {
        YunExpressRequests.CreateShipment req = new YunExpressRequests.CreateShipment(
                "BPA", "NX-1", "KG", "CM", "W", "PDF", List.of(), null, List.of(), null,
                List.of(new YunExpressRequests.ExtraService("V1", "云途预缴")));

        String json = mapper.writeValueAsString(req);

        assertThat(json).contains("\"extra_services\"").contains("\"extra_code\":\"V1\"")
                .contains("\"extra_value\":\"云途预缴\"");
    }

    @Test
    void sinServicioDePrepagoElCampoNoAparece() throws IOException {
        // Mandar `extra_services: []` no es lo mismo que no mandarlo: hay validaciones del transportista
        // que rechazan el array vacío.
        YunExpressRequests.CreateShipment req = new YunExpressRequests.CreateShipment(
                "BPA", "NX-1", "KG", "CM", "W", "PDF", List.of(), null, List.of(), null, null);

        assertThat(mapper.writeValueAsString(req)).doesNotContain("extra_services");
    }

    @Test
    void unBultoSinMedidasSoloDeclaraElPeso() throws IOException {
        YunExpressRequests.Parcel parcel =
                new YunExpressRequests.Parcel(new BigDecimal("1.000"), null, null, null);

        String json = mapper.writeValueAsString(parcel);

        assertThat(json).contains("\"weight\":1.000").doesNotContain("length").doesNotContain("width");
    }

    @Test
    void laSuscripcionYLaAnulacionUsanSusNombresDeCampo() throws IOException {
        assertThat(mapper.writeValueAsString(new YunExpressRequests.SubscribeTracking(
                List.of("YT2621101299000012"), "A", List.of("Y"))))
                .contains("\"waybill_numbers\":[\"YT2621101299000012\"]")
                .contains("\"subscribe_type\":\"A\"")
                .contains("\"query_type\":[\"Y\"]");
        assertThat(mapper.writeValueAsString(new YunExpressRequests.CancelShipment("YT1")))
                .isEqualTo("{\"waybill_number\":\"YT1\"}");
    }
}
