package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.YunExpressFulfillmentService.ParcelDeclaration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Declaración aduanera que viaja en {@code declaration_info[]} al crear el envío.
 *
 * <p>El caso que protege este test se descubrió despachando un pedido real contra el sandbox: YunExpress
 * rechazó la guía con {@code CName : 必填，且不得为纯数字或纯字母}. El nombre chino no es opcional y no
 * vale con texto latino — y en el catálogo actual la columna {@code product.title_zh} está poblada con el
 * título en español, así que un CName "presente pero sin ideogramas" es exactamente el error a detectar.
 */
class YunExpressCustomsDeclarationTest {

    private final YunExpressFulfillmentService service =
            new YunExpressFulfillmentService(null, null, null, null, null, null);

    private static ParcelDeclaration linea(String eName, String cName) {
        return new ParcelDeclaration(eName, cName, "6109100000", 1, 12.5, "USD", 0.3,
                "Cotton", "Daily wear", "https://example.com/p/1", "SKU-1");
    }

    @Test
    void detectaElNombreChinoAusenteOEnAlfabetoLatino() {
        List<ParcelDeclaration> parcels = List.of(
                linea("Men's quartz watch", "Reloj de pulsera para hombre"),  // el bug real: español en CName
                linea("Mesh office chair", null),
                linea("Cotton T-shirt", "1234"));

        List<String> gaps = service.customsGaps(parcels);

        assertThat(gaps).hasSize(3).allMatch(g -> g.startsWith("sin nombre en chino (CName)"));
    }

    @Test
    void unaDeclaracionCompletaNoTieneHuecos() {
        assertThat(service.customsGaps(List.of(linea("Men's quartz watch", "男士石英手表长方形表壳不锈钢表带"))))
                .isEmpty();
    }

    @Test
    void reconoceIdeogramasYRechazaTextoLatinoONumerico() {
        assertThat(YunExpressFulfillmentService.hasChinese("男士石英手表")).isTrue();
        // Mezcla habitual en los títulos de 1688: ideogramas + cifras/letras.
        assertThat(YunExpressFulfillmentService.hasChinese("2024新款 T恤")).isTrue();
        assertThat(YunExpressFulfillmentService.hasChinese("Reloj de pulsera")).isFalse();
        assertThat(YunExpressFulfillmentService.hasChinese("123456")).isFalse();
        assertThat(YunExpressFulfillmentService.hasChinese("")).isFalse();
        assertThat(YunExpressFulfillmentService.hasChinese(null)).isFalse();
    }

    @Test
    void sigueDetectandoElRestoDeHuecosAduaneros() {
        ParcelDeclaration sinPartida = new ParcelDeclaration("Watch", "手表", null, 1, 12.5, "USD", 0.3,
                null, null, null, "SKU-2");
        ParcelDeclaration sinPesoNiValor = new ParcelDeclaration("Watch", "手表", "9102190000", 1, 0.0, "USD", 0.0,
                null, null, null, "SKU-3");

        assertThat(service.customsGaps(List.of(sinPartida)))
                .anyMatch(g -> g.startsWith("sin partida arancelaria"));
        assertThat(service.customsGaps(List.of(sinPesoNiValor)))
                .anyMatch(g -> g.startsWith("sin peso unitario"))
                .anyMatch(g -> g.startsWith("sin valor declarado"));
    }
}
