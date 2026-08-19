package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.cj;

import com.nexaplatform.dropshipping.domain.model.ShippingOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lectura de lo que CJ cotiza, y construcción de lo que se le pregunta.
 *
 * <p>Las respuestas son <b>reales</b>, capturadas contra la API el 18-ago-2026 pidiendo portes de un
 * bulto de 500 g y 20×15×5 cm de China a España. De esa sesión salieron dos hallazgos que no están en la
 * documentación y que hacen fallar la integración <b>en silencio</b>, porque CJ responde
 * {@code code: 200} y {@code result: true} en los dos casos:
 *
 * <ul>
 *   <li><b>El peso va en GRAMOS.</b> Con {@code weight: 500} devuelve 18 opciones; con {@code 0.5},
 *       interpretándolo como kilos, devuelve <b>cero</b> y dice que todo fue bien.</li>
 *   <li><b>{@code shippingMode} y {@code platforms} no se mandan.</b> Con ellos la respuesta trae cero
 *       opciones; sin ellos, las 18. Y si se manda {@code shippingMode} sin {@code platforms}, CJ
 *       rechaza la petición.</li>
 * </ul>
 *
 * <p>Es la misma clase de trampa que ya costó tiempo con YunExpress —responder «correcto» con datos que
 * no sirven—, y por eso el cuerpo de la petición se comprueba aquí campo a campo: una lista vacía de
 * opciones se ve igual que un destino sin cobertura, así que el fallo no se notaría hasta que alguien
 * dijera que el checkout no ofrece envíos.
 */
class CjFreightCalculateTest {

    /** Dos opciones reales de las 18 que devolvió CJ para España, recortadas a los campos que se usan. */
    private static final String RESPUESTA_REAL = """
            {"code":200,"result":true,"message":"Success","data":[
              {"arrivalTime":"8-15","postage":6.13,"postageCNY":38.0,"discountFee":7.67,
               "remoteFee":0,"totalPostageFee":7.67,"optionId":"1868922929754472449","error":"","errorEn":"",
               "option":{"id":"1868922929754472449","enName":"YunExpress Ordinary","cnName":"云途YunExpress特惠普货"},
               "channel":{"id":"12","enName":"促佳云途yunExpress全球专线挂号（特惠普货）"},
               "taxesFee":null,"clearanceOperationFee":null,"tariff":null},
              {"arrivalTime":"4-8","postage":7.10,"postageCNY":44.0,"discountFee":8.83,
               "remoteFee":0,"totalPostageFee":8.83,"optionId":"1564849338719199233","error":"","errorEn":"",
               "option":{"id":"1564849338719199233","enName":"CJPacket Ordinary","cnName":"CJ航空挂号小包"},
               "channel":{"id":"13","enName":"CJPacket"},
               "taxesFee":null,"clearanceOperationFee":null,"tariff":null}
            ]}
            """;

    // ------------------------------------------------------------------ lo que se lee

    @Test
    @DisplayName("cada opción de CJ se convierte en una forma de envío con su precio y su plazo")
    void leeLasOpcionesReales() {
        List<ShippingOption> opciones = CjFreightReader.leer(RESPUESTA_REAL);

        assertThat(opciones).hasSize(2);
        assertThat(opciones.get(0).name()).isEqualTo("YunExpress Ordinary");
        assertThat(opciones.get(0).amountUsdCents())
                .as("se cobra totalPostageFee (7,67), no postage (6,13): la diferencia es el recargo")
                .isEqualTo(767);
        assertThat(opciones.get(0).etaMinDays()).isEqualTo(8);
        assertThat(opciones.get(0).etaMaxDays()).isEqualTo(15);
        assertThat(opciones.get(1).amountUsdCents()).isEqualTo(883);
    }

    @Test
    @DisplayName("el código de la opción es el optionId, que es lo que identifica la línea en CJ")
    void elCodigoEsElOptionId() {
        assertThat(CjFreightReader.leer(RESPUESTA_REAL).get(0).code()).isEqualTo("1868922929754472449");
    }

    @ParameterizedTest(name = "arrivalTime \"{0}\" → de {1} a {2} días")
    @CsvSource({
            "8-15,  8, 15",
            "4-8,   4,  8",
            "12-50, 12, 50",
            // Un solo número: mismo mínimo que máximo, en vez de dejar el plazo a cero.
            "7,     7,  7",
    })
    @DisplayName("el plazo llega como rango de texto y se parte en dos números")
    void parteElPlazo(String arrivalTime, int minimo, int maximo) {
        String respuesta = plantilla(arrivalTime, "7.67");

        ShippingOption opcion = CjFreightReader.leer(respuesta).get(0);

        assertThat(opcion.etaMinDays()).isEqualTo(minimo);
        assertThat(opcion.etaMaxDays()).isEqualTo(maximo);
    }

    @Test
    @DisplayName("los céntimos se redondean al más cercano, no truncando")
    void redondeaAlCentimoMasCercano() {
        // 7,675 $ son 767,5 céntimos: truncar daría 767 y regalaría medio céntimo por envío.
        assertThat(CjFreightReader.leer(plantilla("8-15", "7.675")).get(0).amountUsdCents()).isEqualTo(768);
        assertThat(CjFreightReader.leer(plantilla("8-15", "7.674")).get(0).amountUsdCents()).isEqualTo(767);
    }

    // ------------------------------------------------------------------ lo que se descarta

    @Test
    @DisplayName("una opción que CJ marca con error no se le enseña al cliente")
    void descartaLasOpcionesConError() {
        String conError = """
                {"code":200,"result":true,"data":[
                  {"arrivalTime":"8-15","totalPostageFee":7.67,"optionId":"1","error":"x",
                   "errorEn":"Weight exceeds the limit","option":{"enName":"CJPacket"}}]}
                """;

        assertThat(CjFreightReader.leer(conError))
                .as("cotizar una línea que la propia CJ rechaza es prometer un envío que no saldrá")
                .isEmpty();
    }

    @Test
    @DisplayName("sin precio no hay opción: no se enseña un envío gratis por un campo vacío")
    void descartaLasOpcionesSinPrecio() {
        String sinPrecio = """
                {"code":200,"result":true,"data":[
                  {"arrivalTime":"8-15","totalPostageFee":null,"optionId":"1","option":{"enName":"CJPacket"}}]}
                """;

        assertThat(CjFreightReader.leer(sinPrecio)).isEmpty();
    }

    @Test
    @DisplayName("una respuesta correcta pero vacía se entiende como «sin cobertura», no como error")
    void listaVaciaEsSinCobertura() {
        assertThat(CjFreightReader.leer("{\"code\":200,\"result\":true,\"data\":[]}")).isEmpty();
    }

    @Test
    @DisplayName("si CJ responde con error, no se inventan opciones")
    void errorDeCj() {
        String error = """
                {"code":1600300,"result":false,"message":"reqDTOS[0].platforms must be not empty",
                 "data":null}
                """;

        assertThat(CjFreightReader.leer(error)).isEmpty();
    }

    // ------------------------------------------------------------------ lo que se pregunta

    @Test
    @DisplayName("el peso viaja en GRAMOS: en kilos, CJ contesta «correcto» y no cotiza nada")
    void elPesoViajaEnGramos() {
        String cuerpo = CjFreightReader.cuerpoDeConsulta("CN", "ES", "28001", "Madrid", "Madrid",
                500, 20, 15, 5, 2500);

        assertThat(cuerpo)
                .as("medido contra la API: con 500 devuelve 18 opciones y con 0.5 devuelve cero")
                .contains("\"weight\":500");
    }

    @Test
    @DisplayName("NO se manda shippingMode ni platforms: con ellos la respuesta viene vacía")
    void noSeMandaShippingMode() {
        String cuerpo = CjFreightReader.cuerpoDeConsulta("CN", "ES", "28001", "Madrid", "Madrid",
                500, 20, 15, 5, 2500);

        assertThat(cuerpo)
                .as("con shippingMode=1 y platforms CJ devolvió 0 opciones diciendo que todo fue bien")
                .doesNotContain("shippingMode")
                .doesNotContain("platforms");
    }

    @Test
    @DisplayName("el importe de la mercancía va en unidades de moneda, no en céntimos")
    void elValorDeclaradoVaEnUnidades() {
        String cuerpo = CjFreightReader.cuerpoDeConsulta("CN", "ES", "28001", "Madrid", "Madrid",
                500, 20, 15, 5, 2500);

        assertThat(cuerpo)
                .as("2.500 céntimos son 25,00 $; mandarlos tal cual declararía cien veces el valor real")
                .contains("\"totalGoodsAmount\":25.00");
    }

    @Test
    @DisplayName("el destino y el origen viajan como código de dos letras")
    void origenYDestino() {
        String cuerpo = CjFreightReader.cuerpoDeConsulta("CN", "ES", "28001", "Madrid", "Madrid",
                500, 20, 15, 5, 2500);

        assertThat(cuerpo).contains("\"srcAreaCode\":\"CN\"").contains("\"destAreaCode\":\"ES\"");
    }

    /** Una respuesta de una sola opción con el plazo y el precio indicados. */
    private static String plantilla(String arrivalTime, String precio) {
        return """
                {"code":200,"result":true,"data":[
                  {"arrivalTime":"%s","totalPostageFee":%s,"optionId":"1","error":"",
                   "option":{"enName":"CJPacket Ordinary"}}]}
                """.formatted(arrivalTime, precio);
    }
}
