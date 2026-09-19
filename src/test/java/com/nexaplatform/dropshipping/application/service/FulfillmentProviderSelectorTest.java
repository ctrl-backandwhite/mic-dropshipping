package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.enums.TaxMode;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A quién se le pide la guía de un pedido.
 *
 * <p>Desde que conviven dos transportistas, el pedido guarda cuál de ellos cobró el porte. Elegir mal
 * aquí no da error: se cobra un porte y se paga otro, y el cliente recibe el paquete de quien no eligió
 * —o no lo recibe—. Por eso el caso «no sé quién es» no cae en ninguno, en vez de caer en el primero.
 *
 * <p>Los pedidos anteriores a que existiera ese dato sí caen en el transportista primario: son los que ya
 * están en marcha y todos son suyos.
 */
class FulfillmentProviderSelectorTest {

    private final FulfillmentProvider yunExpress = new TransportistaDePrueba("YUNEXPRESS");
    private final FulfillmentProvider segundo = new TransportistaDePrueba("SEGUNDO");

    private final FulfillmentProviderSelector selector =
            new FulfillmentProviderSelector(List.of(yunExpress, segundo), yunExpress);

    @Test
    void eligeAlTransportistaQueCobroElPorte() {
        assertThat(selector.para(pedidoDe("SEGUNDO"))).contains(segundo);
        assertThat(selector.para(pedidoDe("YUNEXPRESS"))).contains(yunExpress);
    }

    @Test
    void unPedidoSinTransportistaAnotadoVaAlPrimario() {
        // Los pedidos anteriores a la columna: todos eran de YunExpress y hay que poder despacharlos.
        assertThat(selector.para(pedidoDe(null))).contains(yunExpress);
        assertThat(selector.para(pedidoDe("   "))).contains(yunExpress);
    }

    @Test
    void noEligeANadieSiElTransportistaAnotadoNoEstaDisponible() {
        // SEGUNDO apagado, o un valor que ya no existe. Despachar por el otro sería pagar un porte distinto
        // del que se cobró, así que aquí no se sustituye: se devuelve vacío y quien llame decide.
        assertThat(selector.para(pedidoDe("DHL"))).isEmpty();
        assertThat(selector.para(pedidoDe("DESCONOCIDO"))).isEmpty();
    }

    @Test
    void encuentraAUnTransportistaPorSuNombreCuandoNoHayPedidoEnLaMano() {
        // El webhook de un transportista llega sin pedido: trae su propio identificador y hay que
        // localizar a quien sabe descifrarlo.
        assertThat(selector.llamado("YUNEXPRESS")).contains(yunExpress);
        assertThat(selector.llamado("SEGUNDO")).contains(segundo);
        assertThat(selector.llamado("DHL")).isEmpty();
        assertThat(selector.llamado(null)).isEmpty();
    }

    @Test
    void encuentraATransportistaPorSuTipoCuandoHaceFaltaSuImplementacionConcreta() {
        // El webhook de un transportista llega con su formato y su cifrado, y eso lo sabe su clase, no
        // el contrato común. Se busca por tipo y no por nombre porque lo que se necesita es justo esa
        // implementación: el nombre es un dato suyo, el tipo es lo que garantiza que sabe descifrarlo.
        assertThat(selector.deTipo(TransportistaDePrueba.class)).isNotEmpty();
        assertThat(selector.deTipo(FulfillmentProvider.class)).isNotEmpty();
    }

    @Test
    void reconoceElNombreConEspaciosOEnMinusculas() {
        // El valor viene de una columna de texto, no de un enum.
        assertThat(selector.para(pedidoDe("  segundo  "))).contains(segundo);
        assertThat(selector.para(pedidoDe("YunExpress"))).contains(yunExpress);
    }

    private static Order pedidoDe(String transportista) {
        Order pedido = new Order();
        pedido.setShippingCarrier(transportista);
        return pedido;
    }

    /** Un transportista que solo sabe decir cómo se llama: es lo único que mira el selector. */
    private record TransportistaDePrueba(String nombre) implements FulfillmentProvider {

        @Override
        public String nombre() {
            return nombre;
        }

        @Override
        public boolean isSupported(String countryCode) {
            return true;
        }

        @Override
        public List<SupportedCountry> supportedCountries() {
            return List.of();
        }

        @Override
        public ShippingQuote quote(String countryCode, ParcelSpec parcel) {
            return null;
        }

        @Override
        public List<FulfillmentResult> createShipments(Order order) {
            return List.of();
        }

        @Override
        public TrackingSnapshot track(String trackingNumber, Instant forwardedAt, String countryCode) {
            return null;
        }

        @Override
        public TaxMode taxModeFor(String countryCode) {
            return TaxMode.DDP;
        }
    }
}
