package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.controller.TrackingController;
import com.nexaplatform.dropshipping.api.mapper.TrackingViewMapper;
import com.nexaplatform.dropshipping.application.service.FulfillmentService;
import com.nexaplatform.dropshipping.application.service.FulfillmentService.TrackingView;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentSyncScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Relanzar un envío no puede fallar porque el transportista esté caído.
 *
 * <p>El endpoint relanza el envío y después refresca el seguimiento. Ese refresco llama a la API del
 * carrier, y justo después de crear una guía YunExpress responde 503 a la consulta de trazabilidad —se
 * ha visto certificando—. La excepción subía y el panel enseñaba un error rojo sobre una operación que
 * SÍ había funcionado.
 *
 * <p>El daño no es cosmético: ante el error el administrador vuelve a pulsar, y cada pulsación relanza
 * el envío. Son guías de más creadas y pagadas en el transportista.
 */
@DisplayName("Relanzar el envío sobrevive a un fallo del transportista")
class RetryFulfillmentNoFallaPorElCarrierTest {

    private FulfillmentService fulfillmentService;
    private FulfillmentSyncScheduler syncScheduler;
    private TrackingController controller;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        fulfillmentService = mock(FulfillmentService.class);
        syncScheduler = mock(FulfillmentSyncScheduler.class);
        controller = new TrackingController(fulfillmentService, syncScheduler, mock(TrackingViewMapper.class));
        orderId = UUID.randomUUID();

        when(fulfillmentService.adminTrackingView(orderId)).thenReturn(
                new TrackingView("FORWARDED", "YUNEXPRESS", "YT-NUEVA", null, null, List.of(), List.of(), List.of()));
    }

    @Test
    void siElCarrierNoContestaElReintentoSigueDandoRespuestaBuena() {
        doThrow(new IllegalStateException("YunExpress HTTP 503 en /v1/track-service/info/get")).when(syncScheduler)
                .syncOrderById(any());

        ResponseEntity<TrackingView> res = controller.retryFulfillment(orderId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Y lo que se devuelve es el último estado conocido, con la guía que el reintento acaba de crear.
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().trackingNumber()).isEqualTo("YT-NUEVA");
    }

    @Test
    void elReintentoSeLanzaAntesDeRefrescar() {
        // Si se refrescara primero, un carrier lento retrasaría lo único que de verdad importa aquí.
        doThrow(new IllegalStateException("carrier caído")).when(syncScheduler).syncOrderById(any());

        controller.retryFulfillment(orderId);

        verify(fulfillmentService).retryFulfillment(orderId);
    }

    @Test
    void conElCarrierSanoElRefrescoSiSeHace() {
        controller.retryFulfillment(orderId);

        verify(syncScheduler).syncOrderById(orderId);
        verify(fulfillmentService).retryFulfillment(orderId);
    }
}
