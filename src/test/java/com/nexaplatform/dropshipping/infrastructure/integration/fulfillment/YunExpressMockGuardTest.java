package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import com.nexaplatform.dropshipping.application.service.CustomsValuationService;
import com.nexaplatform.dropshipping.application.service.CustomsValuationService.CustomsValuation;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.enums.OverThresholdPolicy;
import com.nexaplatform.dropshipping.domain.enums.TaxMode;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.FulfillmentResult;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.TrackingSnapshot;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CainiaoZoneRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * El modo simulado NO puede actuar en producción.
 *
 * <p>El mock inventa una guía ({@code YT<hex>YE}) y hace avanzar el tracking solo hasta "entregado". Eso
 * es justo lo que se quiere en desarrollo y un desastre en producción: si caducan las credenciales o
 * alguien deja {@code enabled=false}, la plataforma daría por entregados pedidos que nunca salieron y
 * mandaría al cliente el correo de entrega. Se comprobó en real — un pedido rechazado por el carrier
 * acabó marcado como DELIVERED con una guía inexistente.
 */
class YunExpressMockGuardTest {

    private YunExpressFulfillmentService serviceOn(String... activeProfiles) {
        CainiaoZoneRepository zones = Mockito.mock(CainiaoZoneRepository.class);
        CustomsValuationService customs = Mockito.mock(CustomsValuationService.class);
        YunExpressClient client = Mockito.mock(YunExpressClient.class);
        lenient().when(zones.findByCountryCodeIgnoreCase(anyString())).thenReturn(Optional.empty());
        lenient().when(customs.valuate(anyString(), anyInt(), anyInt()))
                .thenReturn(new CustomsValuation("ES", TaxMode.DDP, 1000, false,
                        OverThresholdPolicy.ALLOW, 0, false));
        lenient().when(client.hasCredentials()).thenReturn(false);

        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(activeProfiles);
        YunExpressFulfillmentService service =
                new YunExpressFulfillmentService(zones, client, customs, null, null, environment);
        ReflectionTestUtils.setField(service, "enabled", false);
        ReflectionTestUtils.setField(service, "mockStageMinutes", 2L);
        return service;
    }

    private static Order pedido() {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setOrderNumber("NX-1784936692-7159");
        order.setShippingCountry("ES");
        order.setItems(new ArrayList<>());
        return order;
    }

    @Test
    void enProduccionNoSeInventaUnaGuia() {
        YunExpressFulfillmentService service = serviceOn("pro");

        assertThatThrownBy(() -> service.createShipment(pedido()))
                .isInstanceOf(FulfillmentFailure.class)
                .hasMessageContaining("no se generan envíos simulados");
    }

    @Test
    void enPreproduccionTampoco() {
        YunExpressFulfillmentService service = serviceOn("pre");

        assertThatThrownBy(() -> service.createShipment(pedido())).isInstanceOf(FulfillmentFailure.class);
    }

    @Test
    void esUnFalloTransitorioParaQueSeRecupereSolOAlVolverElServicio() {
        YunExpressFulfillmentService service = serviceOn("pro");

        // Transitorio a propósito: si mañana se arreglan las credenciales, el envío debe salir sin que
        // nadie tenga que rehabilitarlo a mano.
        assertThatThrownBy(() -> service.createShipment(pedido()))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(FulfillmentFailure.class))
                .matches(f -> !f.isPermanent());
    }

    @Test
    void enProduccionElSeguimientoNoAvanzaSolo() {
        YunExpressFulfillmentService service = serviceOn("pro");

        // Una hora larga después del despacho: con el mock, esto ya estaría "entregado".
        TrackingSnapshot snap = service.track("YT000000000000YE", Instant.now().minusSeconds(7200), "ES");

        assertThat(snap.currentStatus()).isEqualTo(OrderStatus.FORWARDED);
        assertThat(snap.steps()).isEmpty();
    }

    @Test
    void enLocalElMockSigueFuncionandoParaPoderProbarElFlujo() {
        YunExpressFulfillmentService service = serviceOn("local");

        FulfillmentResult result = service.createShipment(pedido());

        assertThat(result.trackingNumber()).startsWith("YT").endsWith("YE");
        assertThat(service.track(result.trackingNumber(), Instant.now().minusSeconds(7200), "ES").currentStatus())
                .isEqualTo(OrderStatus.DELIVERED);
    }
}
