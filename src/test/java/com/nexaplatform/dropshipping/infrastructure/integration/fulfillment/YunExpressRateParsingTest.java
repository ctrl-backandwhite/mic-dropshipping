package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.YunExpressFulfillmentService.RateOption;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lectura de la simulación de tarifa ({@code /v1/price-trial/get}).
 *
 * <p>YunExpress devuelve el precio DESGLOSADO por concepto de coste ({@code fee_name}: flete, registro,
 * arancel...), una línea por concepto y canal. Si se tomara una sola línea como precio del canal, el envío
 * se cotizaría por debajo de su coste y la diferencia saldría del margen en cada pedido.
 */
class YunExpressRateParsingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private List<RateOption> parse(String json) throws IOException {
        return YunExpressFulfillmentService.parseRates(mapper.readTree(json));
    }

    @Test
    void sumaLosConceptosDeCosteDeCadaCanal() throws IOException {
        String result = """
                [
                  {"product_code":"BKZXR","product_name":"Global line","fee_name":"E1",
                   "calculate_amount":8,"currency":"RMB","interval_day":"3-8"},
                  {"product_code":"BKZXR","product_name":"Global line","fee_name":"E2",
                   "calculate_amount":21,"currency":"RMB","interval_day":"3-8"},
                  {"product_code":"BPA","product_name":"Small packet","fee_name":"E1",
                   "calculate_amount":11.1,"currency":"RMB","interval_day":"7-15"}
                ]""";

        List<RateOption> options = parse(result);

        assertThat(options).hasSize(2);
        assertThat(options.get(0).productCode()).isEqualTo("BKZXR");
        assertThat(options.get(0).amount()).isEqualByComparingTo(new BigDecimal("29"));
        assertThat(options.get(1).amount()).isEqualByComparingTo(new BigDecimal("11.1"));
    }

    @Test
    void rmbSeNormalizaAlCodigoIsoQueEntiendeElConversor() throws IOException {
        String result = """
                [{"product_code":"BPA","fee_name":"E1","calculate_amount":10,"currency":"RMB","interval_day":"5"}]""";

        assertThat(parse(result).get(0).currency()).isEqualTo("CNY");
    }

    @Test
    void prefiereElImporteYaConvertidoALaDivisaDeFacturacion() throws IOException {
        String result = """
                [{"product_code":"BPA","fee_name":"E1","calculate_amount":80,"currency":"RMB",
                  "convert_amount":11.2,"convert_currency":"USD","interval_day":"5"}]""";

        RateOption option = parse(result).get(0);

        assertThat(option.amount()).isEqualByComparingTo(new BigDecimal("11.2"));
        assertThat(option.currency()).isEqualTo("USD");
    }

    @Test
    void ignoraLineasSinCanal() throws IOException {
        String result = """
                [{"fee_name":"E1","calculate_amount":10,"currency":"RMB"},
                 {"product_code":"BPA","fee_name":"E1","calculate_amount":10,"currency":"RMB"}]""";

        assertThat(parse(result)).hasSize(1);
    }

    @Test
    void interpretaElPlazoDeEntrega() {
        assertThat(YunExpressFulfillmentService.parseEtaDays("3-8")).containsExactly(3, 8);
        assertThat(YunExpressFulfillmentService.parseEtaDays("7")).containsExactly(7, 7);
        assertThat(YunExpressFulfillmentService.parseEtaDays("")).containsExactly(0, 0);
        assertThat(YunExpressFulfillmentService.parseEtaDays(null)).containsExactly(0, 0);
        assertThat(YunExpressFulfillmentService.parseEtaDays("n/d")).containsExactly(0, 0);
    }

    @Test
    void alCrearElEnvioSeUsaLaGuiaCuandoAunNoHayNumeroDeSeguimiento() throws IOException {
        // Caso REAL del sandbox: el canal aún no ha asignado tracking_number al dar de alta el envío.
        String conNulo = """
                {"waybill_number":"YT2621101299000001","tracking_number":null}""";
        String vacio = """
                {"waybill_number":"YT2621101299000001","tracking_number":""}""";
        String conTracking = """
                {"waybill_number":"YT2621101299000001","tracking_number":"LX123456789ES"}""";

        String guia = "YT2621101299000001";
        assertThat(YunExpressFulfillmentService.trackingOf(mapper.readTree(conNulo), guia)).isEqualTo(guia);
        assertThat(YunExpressFulfillmentService.trackingOf(mapper.readTree(vacio), guia)).isEqualTo(guia);
        assertThat(YunExpressFulfillmentService.trackingOf(mapper.readTree(conTracking), guia))
                .isEqualTo("LX123456789ES");
    }

    @Test
    void laEtiquetaPrefiereElContenidoEmbebidoSobreLaUrlQueCaduca() throws IOException {
        String soloUrl = """
                {"url":"https://example.com/label.pdf","label_type":"PDF","label_string":""}""";
        String embebido = """
                {"url":"https://example.com/label.pdf","label_string":"JVBERi0xLjQK"}""";

        assertThat(YunExpressFulfillmentService.labelOf(mapper.readTree(soloUrl)))
                .isEqualTo("https://example.com/label.pdf");
        assertThat(YunExpressFulfillmentService.labelOf(mapper.readTree(embebido))).isEqualTo("JVBERi0xLjQK");
    }

    @Test
    void parteElNombreEnNombreYApellidosParaLaEtiqueta() {
        assertThat(YunExpressFulfillmentService.splitName("Ana María López Ruiz")).containsExactly("Ana",
                "María López Ruiz");
        // Con una sola palabra se repite: dejar el apellido vacío deja la etiqueta incompleta.
        assertThat(YunExpressFulfillmentService.splitName("Madonna")).containsExactly("Madonna", "Madonna");
        assertThat(YunExpressFulfillmentService.splitName(null)).containsExactly("Customer", "Customer");
        assertThat(YunExpressFulfillmentService.splitName("  ")).containsExactly("Customer", "Customer");
    }
}
