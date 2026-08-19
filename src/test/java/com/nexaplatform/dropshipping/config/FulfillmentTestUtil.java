package com.nexaplatform.dropshipping.config;

import com.nexaplatform.dropshipping.application.service.FulfillmentProviderSelector;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockingDetails;

/** Utilidades para las pruebas que montan el servicio de envíos. */
public final class FulfillmentTestUtil {

    private FulfillmentTestUtil() {
    }

    /**
     * El escenario de un solo transportista, que es el de casi todas las pruebas de envíos: el mismo
     * atiende a cualquier pedido, tenga o no anotado quién lo lleva.
     *
     * <p>Existe porque el servicio ya no recibe «el» transportista sino el selector que decide cuál toca
     * para cada pedido. Las pruebas que no van de eso no tienen por qué enterarse.
     *
     * <p>Si lo que llega es un simulacro, se le deja además <b>listo para despachar</b>. Sin esto, un
     * simulacro contesta {@code false} a {@code readyToShip} —el valor por defecto de un booleano— y el
     * despacho se queda esperando para siempre, con lo que una prueba de reintentos o de avisos fallaría
     * por un motivo que no tiene nada que ver con lo que quiere comprobar. Una prueba que sí vaya de
     * cuándo se puede despachar debe decirlo ella misma, después de llamar aquí.
     */
    public static FulfillmentProviderSelector unSoloTransportista(FulfillmentProvider transportista) {
        if (mockingDetails(transportista).isMock()) {
            lenient().when(transportista.readyToShip(any())).thenReturn(true);
        }
        return new FulfillmentProviderSelector(List.of(transportista), transportista);
    }
}
