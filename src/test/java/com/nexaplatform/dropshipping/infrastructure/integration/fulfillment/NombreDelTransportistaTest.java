package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import com.nexaplatform.dropshipping.api.controller.ShippingQuoteController;
import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.cj.CjFulfillmentService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cómo se llama cada transportista, que es el dato del que cuelga todo lo demás.
 *
 * <p>Ese nombre viaja por tres sitios que no se ven entre sí: el enrutador lo estampa en cada opción de
 * envío, el pedido lo guarda al cobrar para saber a quién pedirle luego la guía, y la pantalla lo traduce
 * a algo legible. Si uno de los tres usa otro valor, no salta ningún error: simplemente se despacha por
 * quien no cobró, o el cliente lee «Transporte estándar» donde debería leer el nombre real.
 *
 * <p>El riesgo no es teórico. {@code FulfillmentProvider.nombre()} trae un valor por defecto
 * ({@code DESCONOCIDO}) para no obligar a tocar las implementaciones antiguas, así que un transportista
 * que se olvide de declararse compila, arranca y funciona hasta el día del despacho.
 */
class NombreDelTransportistaTest {

    private final YunExpressFulfillmentService yunExpress = new YunExpressFulfillmentService(null, null, null,
            new CustomsDutyLinesService(null), null, null, null, null);

    private final CjFulfillmentService cj = new CjFulfillmentService(null, null, null, null, null);

    @Test
    void yunExpressSeIdentificaComoYunexpress() {
        assertThat(yunExpress.nombre()).isEqualTo("YUNEXPRESS");
    }

    @Test
    void cjSeIdentificaComoCj() {
        assertThat(cj.nombre()).isEqualTo("CJ");
    }

    @Test
    void ningunTransportistaSeQuedaConElNombrePorDefecto() {
        // El valor por defecto de la interfaz existe por compatibilidad, pero quien aparece en un pedido
        // tiene que declararse: con «DESCONOCIDO» no se le puede pedir la guía a nadie.
        assertThat(List.of(yunExpress.nombre(), cj.nombre())).doesNotContain("DESCONOCIDO");
    }

    @Test
    void laPantallaSabeTraducirElNombreDeLosDos() {
        // Ata el enrutador con la pantalla: lo que se estampa en la opción es lo que el enum traduce.
        assertThat(ShippingQuoteController.NombreDelTransportista.visibleDe(yunExpress.nombre()))
                .isEqualTo("YunExpress");
        assertThat(ShippingQuoteController.NombreDelTransportista.visibleDe(cj.nombre()))
                .isEqualTo("CJ Dropshipping");
    }
}
