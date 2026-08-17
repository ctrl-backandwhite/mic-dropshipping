package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.YunExpressFulfillmentService.RateOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Solo se ofrecen los canales que el contrato permite usar.
 *
 * <p>El transportista cotiza para nuestra cuenta más líneas de las que se pueden usar: cada una admite
 * una clase de mercancía distinta —la de ropa (`FZZXR`) solo textil en bolsa, las de carga general
 * (`THPHR`) mercancía normal sin batería, y hay otras de cosmética, de artículos con batería o de gran
 * volumen que no vienen al caso—. Ofrecer en el checkout un canal que luego no acepta lo que va dentro
 * significa cobrar un envío y descubrir en el almacén que no se puede despachar.
 *
 * <p>La lista es <b>configuración</b> y no código: cuando se amplíe el contrato, se añade el canal en
 * una variable de entorno y no hay que desplegar. Vacía = no se filtra nada, que es lo que necesitan el
 * entorno de pruebas —cuyo canal `BPA` no existe en producción— y cualquier cuenta que no tenga esta
 * restricción.
 */
class YunExpressContractedChannelsTest {

    private static final List<RateOption> COTIZADOS = List.of(
            rate("FZZXR", "55.00", 4, 14),
            rate("THPHR", "57.50", 5, 25),
            rate("BKPHR", "73.00", 3, 8),
            rate("MUZXR", "80.00", 6, 12));

    private static RateOption rate(String code, String amount, int min, int max) {
        return new RateOption(code, code, new BigDecimal(amount), "RMB", min, max);
    }

    private static List<String> codigos(List<RateOption> rates) {
        return rates.stream().map(RateOption::productCode).toList();
    }

    @Test
    @DisplayName("con la lista puesta solo quedan los canales contratados, de más barato a más caro")
    void soloQuedanLosContratados() {
        List<RateOption> r = YunExpressFulfillmentService.contractedRates(COTIZADOS, Set.of("FZZXR", "THPHR"));

        assertThat(codigos(r)).containsExactly("FZZXR", "THPHR");
    }

    @Test
    @DisplayName("sin lista no se filtra nada: hay cuentas y entornos sin esta restricción")
    void sinListaNoSeFiltra() {
        assertThat(codigos(YunExpressFulfillmentService.contractedRates(COTIZADOS, Set.of())))
                .containsExactly("FZZXR", "THPHR", "BKPHR", "MUZXR");
        assertThat(codigos(YunExpressFulfillmentService.contractedRates(COTIZADOS, null)))
                .hasSize(4);
    }

    @Test
    @DisplayName("el código se compara sin distinguir mayúsculas ni espacios de más")
    void toleraMayusculasYEspacios() {
        List<RateOption> r = YunExpressFulfillmentService.contractedRates(COTIZADOS, Set.of(" fzzxr "));

        assertThat(codigos(r)).containsExactly("FZZXR");
    }

    @Test
    @DisplayName("la comparación es exacta: una variante no entra por parecerse a un canal contratado")
    void unaVarianteNoEntraPorParecido() {
        // `FZZXR-AMZ` es la misma línea de ropa por la red de Amazon, y exige SU número de IOSS: que se
        // llame parecido no la hace utilizable. Si algún día se contrata, se añade a la lista y ya está.
        List<RateOption> conVariante = List.of(rate("FZZXR-AMZ", "55.00", 4, 10), rate("THPHR", "57.50", 5, 25));

        assertThat(codigos(YunExpressFulfillmentService.contractedRates(conVariante, Set.of("FZZXR"))))
                .isEmpty();
    }

    @Test
    @DisplayName("un destino sin ningún canal contratado se queda sin opciones, y no se inventa una")
    void unDestinoSinCanalContratadoSeQuedaSinOpciones() {
        // Preferible a ofrecer un canal que no se puede usar: sin opciones, la cotización cae a la tabla
        // de zonas y el destino se puede apagar; con una opción falsa, se cobra un envío imposible.
        List<RateOption> soloNoContratados = List.of(rate("MUZXR", "80.00", 6, 12));

        assertThat(YunExpressFulfillmentService.contractedRates(soloNoContratados, Set.of("FZZXR", "THPHR")))
                .isEmpty();
    }

    @Test
    @DisplayName("una cotización vacía no revienta")
    void toleraLaCotizacionVacia() {
        assertThat(YunExpressFulfillmentService.contractedRates(List.of(), Set.of("FZZXR"))).isEmpty();
        assertThat(YunExpressFulfillmentService.contractedRates(null, Set.of("FZZXR"))).isEmpty();
    }
}
