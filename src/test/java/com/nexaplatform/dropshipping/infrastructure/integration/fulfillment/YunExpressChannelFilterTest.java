package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.YunExpressFulfillmentService.RateOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Qué canales se le pueden ofrecer al cliente cuando el pedido va con el IVA prepagado.
 *
 * <p>La tienda vende DDP: cobra el impuesto en el checkout y el destinatario no paga nada al recibir.
 * Hay dos familias de canales que rompen esa promesa, y como son de las más baratas, «el más barato que
 * cotice» las elige a menudo —en Alemania, Italia, México, Brasil, Colombia y Argentina, medido contra
 * la API el 17-ago-2026—:
 *
 * <ul>
 *   <li><b>Postales</b> ({@code CNDWA}, {@code EUB-SZ}, {@code SZEMS}, {@code SNETK}): el contrato dice
 *       «邮局渠道为DAP模式（税费由收件人支付），暂不支持IOSS申报» — son DAP y no admiten IOSS, así que
 *       el IVA se lo reclaman al cliente aunque ya lo haya pagado.</li>
 *   <li><b>Los {@code -AMZ}</b>: «该产品只支持使用亚马逊平台IOSS号下单» — solo aceptan el número IOSS de
 *       Amazon, que no es el nuestro.</li>
 * </ul>
 *
 * <p>Fuera de la UE, donde no hay IVA prepagado, esos canales sí son utilizables: allí el impuesto lo
 * paga el destinatario de todas formas y descartarlos solo encarecería el envío sin motivo.
 */
class YunExpressChannelFilterTest {

    private static RateOption canal(String code, String amount) {
        return new RateOption(code, "Nombre " + code, new BigDecimal(amount), "RMB", 5, 10);
    }

    /** Lo que cotiza España a 0,5 kg, con sus precios reales. */
    private static List<RateOption> espana() {
        return List.of(canal("FZZXR", "55.00"), // línea de ropa
                canal("FZZXR-AMZ", "55.00"), // ✗ exige IOSS de Amazon
                canal("CNDWA", "57.00"), // ✗ postal, DAP
                canal("THPHR", "57.50"), // línea global
                canal("EUB-SZ", "56.00"), // ✗ postal, DAP
                canal("BKPHR", "66.00"));
    }

    @Test
    @DisplayName("con IVA prepagado descarta los postales y los de Amazon")
    void descartaLosIncompatiblesConElPrepago() {
        List<RateOption> ofrecibles = YunExpressFulfillmentService.deliverableRates(espana(), true);

        assertThat(ofrecibles).extracting(RateOption::productCode).containsExactly("FZZXR", "THPHR", "BKPHR")
                .doesNotContain("CNDWA", "EUB-SZ", "FZZXR-AMZ");
    }

    @Test
    @DisplayName("el más barato que queda es el que se cobra")
    void elMasBaratoUtilizableEsElPrimero() {
        List<RateOption> ofrecibles = YunExpressFulfillmentService.deliverableRates(espana(), true);

        // 55,00 de FZZXR y no los 55,00 del -AMZ ni los 56,00 del postal.
        assertThat(ofrecibles).first().extracting(RateOption::productCode, RateOption::amount).containsExactly("FZZXR",
                new BigDecimal("55.00"));
    }

    @Test
    @DisplayName("sin IVA prepagado se ofrecen todos, también los postales")
    void fueraDelPrepagoNoSeDescartaNada() {
        // En un destino DDU el impuesto lo paga el destinatario en cualquier canal: quitar los postales
        // solo encarecería el envío sin ganar nada.
        List<RateOption> ofrecibles = YunExpressFulfillmentService.deliverableRates(espana(), false);

        assertThat(ofrecibles).hasSize(6);
    }

    @Test
    @DisplayName("las opciones salen ordenadas de más barata a más cara")
    void vienenOrdenadasPorPrecio() {
        List<RateOption> ofrecibles = YunExpressFulfillmentService.deliverableRates(espana(), true);

        assertThat(ofrecibles).extracting(RateOption::productCode, o -> o.amount().toPlainString())
                .containsExactly(tuple("FZZXR", "55.00"), tuple("THPHR", "57.50"), tuple("BKPHR", "66.00"));
    }

    @Test
    @DisplayName("si no queda ningún canal utilizable la lista es vacía, no nula")
    void sinCanalesUtilizablesDevuelveVacio() {
        // Es lo que pasa hoy en Argentina: su único canal es el postal. Devolver vacío deja que el
        // llamante caiga a la tabla de zonas en vez de reventar.
        List<RateOption> soloPostales = List.of(canal("CNDWA", "103.95"), canal("EUB-SZ", "110.00"));

        assertThat(YunExpressFulfillmentService.deliverableRates(soloPostales, true)).isEmpty();
    }

    @Test
    @DisplayName("una lista vacía o nula no revienta")
    void toleraLaAusenciaDeTarifas() {
        assertThat(YunExpressFulfillmentService.deliverableRates(List.of(), true)).isEmpty();
        assertThat(YunExpressFulfillmentService.deliverableRates(null, true)).isEmpty();
    }
}
