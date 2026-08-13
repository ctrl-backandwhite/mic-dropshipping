package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.application.service.CustomsValuationService;
import com.nexaplatform.dropshipping.application.service.CustomsValuationService.CustomsValuation;
import com.nexaplatform.dropshipping.domain.enums.OverThresholdPolicy;
import com.nexaplatform.dropshipping.domain.enums.TaxMode;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.FulfillmentResult;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.ParcelSpec;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CainiaoZoneEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CainiaoZoneRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService.DutyParcel;
import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Alta de envíos en YunExpress: qué se manda, qué se guarda y cómo se clasifica un rechazo.
 *
 * <p>Esta es la frontera en la que un pedido pasa a "despachado". Dar por despachado lo que el carrier no
 * aceptó deja al cliente con un número de seguimiento que no existe, así que aquí NO se tolera el fallo:
 * se propaga clasificado (transitorio = se reintenta solo; permanente = alguien tiene que tocar algo).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov04YunExpressShipmentTest {

    private static final String PATH_CREATE = "/v1/order/package/create";
    private static final String PATH_SUBSCRIBE = "/v1/track-service/subscribe-by-order";
    private static final String PATH_PRICE_TRIAL = "/v1/price-trial/get";

    @Mock
    CainiaoZoneRepository zoneRepository;
    @Mock
    YunExpressClient client;
    @Mock
    CustomsValuationService customsValuation;
    @Mock
    ProductRepository productRepository;
    @Mock
    CurrencyRateService currencyRateService;

    private final ObjectMapper mapper = new ObjectMapper();
    private YunExpressFulfillmentService service;

    @BeforeEach
    void setUp() {
        service = new YunExpressFulfillmentService(zoneRepository, client, customsValuation, new CustomsDutyLinesService(), productRepository,
                currencyRateService, new MockEnvironment());
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "productCode", "BPA");
        ReflectionTestUtils.setField(service, "productGroupCode", "");
        ReflectionTestUtils.setField(service, "labelType", "PDF");
        ReflectionTestUtils.setField(service, "iossNumber", "");
        ReflectionTestUtils.setField(service, "defaultTaxMode", "DDP");
        ReflectionTestUtils.setField(service, "trackingSubscriptionEnabled", true);
        ReflectionTestUtils.setField(service, "trackingSubscribeType", "A");
        ReflectionTestUtils.setField(service, "quoteTimeoutSeconds", 5L);
        ReflectionTestUtils.setField(service, "volumetricDivisor", 6000.0);
        ReflectionTestUtils.setField(service, "volumetricMinCm3", 6000.0);
        ReflectionTestUtils.setField(service, "mockStageMinutes", 2L);
        ReflectionTestUtils.setField(service, "maxParcelWeightGrams", 0);
        ReflectionTestUtils.setField(service, "maxParcelValueCents", 0);
        ReflectionTestUtils.setField(service, "maxParcelUnits", 0);

        when(client.hasCredentials()).thenReturn(true);
        when(zoneRepository.findByCountryCodeIgnoreCase(anyString()))
                .thenReturn(Optional.of(CainiaoZoneEntity.builder().countryCode("ES").countryName("España")
                        .zone("EU").baseCents(500).perKgCents(1000).etaMinDays(5).etaMaxDays(12).enabled(true)
                        .build()));
        when(customsValuation.valuate(anyString(), anyInt(), anyInt(), anyList()))
                .thenReturn(valoracion(false));
        when(client.post(eq(PATH_SUBSCRIBE), any(Object.class))).thenReturn(ok("{\"success\":true}"));
    }

    private static CustomsValuation valoracion(boolean deMinimisExceeded) {
        return new CustomsValuation("ES", TaxMode.DDP, 4500, deMinimisExceeded, OverThresholdPolicy.ALLOW, 0,
                false, "");
    }

    private JsonNode ok(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static OrderItem linea(int qty, int unitPriceCents) {
        OrderItem item = new OrderItem();
        item.setProductId(UUID.randomUUID()); // cada línea es un producto distinto (para contar artículos)
        item.setQuantity(qty);
        item.setUnitPriceCents(unitPriceCents);
        item.setTitleSnapshot("Cotton T-shirt");
        item.setProductTitleZh("棉T恤");
        item.setSkuSnapshot("SKU-1");
        return item;
    }

    private static Order pedido(OrderItem... items) {
        Order order = new Order();
        order.setId(UUID.fromString("11112222-3333-4444-5555-666677778888"));
        order.setOrderNumber("NX-1");
        order.setCurrency("USD");
        order.setShippingCountry("ES");
        order.setShippingFullName("Ana López");
        order.setShippingLine1("Calle Mayor 1");
        order.setShippingCity("Zaragoza");
        order.setShippingPostalCode("50001");
        order.setSubtotalCents(5000);
        order.setDiscountCents(500);
        order.setItems(new ArrayList<>(List.of(items)));
        return order;
    }

    private static ArgumentCaptor<Object> payloadCaptor() {
        return ArgumentCaptor.forClass(Object.class);
    }

    // ── Alta correcta ────────────────────────────────────────────────────────────────────────────

    @Test
    void elEnvioSeDaPorCreadoSoloConLaGuiaQueDevuelveElCarrier() {
        when(client.post(eq(PATH_CREATE), any(Object.class))).thenReturn(ok("""
                {"success":true,"result":{"waybill_number":"YT2621101299000001","tracking_number":"LX1ES"}}"""));

        FulfillmentResult result = service.createShipment(pedido(linea(1, 1200)));

        assertThat(result.fulfillmentRef()).isEqualTo("YT2621101299000001");
        assertThat(result.trackingNumber()).isEqualTo("LX1ES");
        assertThat(result.etaMaxDays()).isEqualTo(12);
    }

    @Test
    void cadaBultoPideSuGuiaConUnNumeroDeClienteDistinto() {
        // El carrier rechaza dos guías con el mismo customer_order_number: sin el sufijo por bulto, el
        // segundo envío de un pedido repartido nunca se crearía.
        ReflectionTestUtils.setField(service, "maxParcelWeightGrams", 1200);
        when(client.post(eq(PATH_CREATE), any(Object.class))).thenReturn(
                ok("{\"success\":true,\"result\":{\"waybill_number\":\"YT-A\"}}"),
                ok("{\"success\":true,\"result\":{\"waybill_number\":\"YT-B\"}}"));

        List<FulfillmentResult> results = service.createShipments(pedido(linea(3, 1000)));

        assertThat(results).hasSize(2);
        assertThat(results).extracting(FulfillmentResult::fulfillmentRef).containsExactly("YT-A", "YT-B");
        assertThat(results).extracting(FulfillmentResult::sequenceNo).containsExactly(1, 2);
        ArgumentCaptor<Object> captor = payloadCaptor();
        verify(client, times(2)).post(eq(PATH_CREATE), captor.capture());
        assertThat(captor.getAllValues()).extracting(p -> ((YunExpressRequests.CreateShipment) p)
                .customerOrderNumber()).containsExactly("NX-1-1", "NX-1-2");
    }

    @Test
    void cadaGuiaGuardaElPesoYElValorQueDeVerdadViajanEnEseBulto() {
        // Si el envío no guarda su peso/valor, el cliente ve ceros y aduana no cuadra con la etiqueta.
        ReflectionTestUtils.setField(service, "maxParcelWeightGrams", 1200);
        when(client.post(eq(PATH_CREATE), any(Object.class))).thenReturn(
                ok("{\"success\":true,\"result\":{\"waybill_number\":\"YT-A\"}}"),
                ok("{\"success\":true,\"result\":{\"waybill_number\":\"YT-B\"}}"));

        List<FulfillmentResult> results = service.createShipments(pedido(linea(3, 1000)));

        assertThat(results).extracting(FulfillmentResult::weightGrams).containsExactly(1000, 500);
        assertThat(results).extracting(FulfillmentResult::declaredValueCents).containsExactly(2000, 1000);
        assertThat(results).extracting(FulfillmentResult::productCode).containsOnly("BPA");
    }

    @Test
    void conElCanalFijadoEnConfiguracionNoSeMalgastaUnaSimulacionDeTarifa() {
        when(client.post(eq(PATH_CREATE), any(Object.class)))
                .thenReturn(ok("{\"success\":true,\"result\":{\"waybill_number\":\"YT-A\"}}"));

        service.createShipment(pedido(linea(1, 1000)));

        verify(client, never()).get(eq(PATH_PRICE_TRIAL), any(), any());
    }

    // ── Rechazos y fallos ────────────────────────────────────────────────────────────────────────

    @Test
    void unRechazoPorReglasDelCanalEsPermanenteYNoSeReintenta() {
        // 02039171 = el bulto no cumple las reglas del canal: reintentarlo solo llena el log.
        when(client.post(eq(PATH_CREATE), any(Object.class))).thenReturn(ok("""
                {"success":false,"code":"02039171","msg":"Order rule verification failed"}"""));
        Order order = pedido(linea(1, 1000));

        assertThatThrownBy(() -> service.createShipment(order))
                .asInstanceOf(InstanceOfAssertFactories.type(FulfillmentFailure.class))
                .matches(FulfillmentFailure::isPermanent);
    }

    @Test
    void unRechazoConCodigoDesconocidoSeReintenta() {
        // Rendirse de más deja un envío sin crear que nadie vuelve a intentar.
        when(client.post(eq(PATH_CREATE), any(Object.class)))
                .thenReturn(ok("{\"success\":false,\"code\":\"09999999\",\"msg\":\"vaya\"}"));
        Order order = pedido(linea(1, 1000));

        assertThatThrownBy(() -> service.createShipment(order))
                .asInstanceOf(InstanceOfAssertFactories.type(FulfillmentFailure.class))
                .matches(f -> !f.isPermanent());
    }

    @Test
    void unFalloDeRedAlCrearElEnvioEsTransitorioPorqueNoSeSabeSiLlegoACrearse() {
        when(client.post(eq(PATH_CREATE), any(Object.class)))
                .thenThrow(new IllegalStateException("connection reset"));
        Order order = pedido(linea(1, 1000));

        assertThatThrownBy(() -> service.createShipment(order))
                .asInstanceOf(InstanceOfAssertFactories.type(FulfillmentFailure.class))
                .matches(f -> !f.isPermanent());
    }

    @Test
    void unaRespuestaCorrectaPeroSinGuiaNoDaElPedidoPorDespachado() {
        when(client.post(eq(PATH_CREATE), any(Object.class)))
                .thenReturn(ok("{\"success\":true,\"result\":{\"waybill_number\":\"\"}}"));
        Order order = pedido(linea(1, 1000));

        assertThatThrownBy(() -> service.createShipment(order))
                .isInstanceOf(FulfillmentFailure.class)
                .hasMessageContaining("no devolvió número de guía");
    }

    @Test
    void unBultoSinGuiaTumbaTodoElPedidoYNoSoloEseEnvio() {
        // Medio pedido despachado es peor que ninguno: las guías creadas se anulan desde el panel.
        ReflectionTestUtils.setField(service, "maxParcelWeightGrams", 1200);
        when(client.post(eq(PATH_CREATE), any(Object.class))).thenReturn(
                ok("{\"success\":true,\"result\":{\"waybill_number\":\"YT-A\"}}"),
                ok("{\"success\":true,\"result\":{\"waybill_number\":\"\"}}"));
        Order order = pedido(linea(3, 1000));

        assertThatThrownBy(() -> service.createShipments(order)).isInstanceOf(FulfillmentFailure.class);
    }

    @Test
    void sinCanalConfiguradoYSinTarifaElFalloEsPermanenteYPideConfiguracion() {
        ReflectionTestUtils.setField(service, "productCode", "");
        when(client.get(eq(PATH_PRICE_TRIAL), any(), any()))
                .thenReturn(ok("{\"success\":false,\"code\":\"02030008\",\"msg\":\"no product\"}"));
        Order order = pedido(linea(1, 1000));

        assertThatThrownBy(() -> service.createShipment(order))
                .asInstanceOf(InstanceOfAssertFactories.type(FulfillmentFailure.class))
                .matches(FulfillmentFailure::isPermanent);
    }

    // ── Suscripción al push de trazabilidad ──────────────────────────────────────────────────────

    @Test
    void unFalloSuscribiendoElPushNoAnulaUnEnvioYaCreado() {
        // El paquete ya está dado de alta; perder la suscripción es degradación (queda el sondeo).
        when(client.post(eq(PATH_CREATE), any(Object.class)))
                .thenReturn(ok("{\"success\":true,\"result\":{\"waybill_number\":\"YT-A\"}}"));
        when(client.post(eq(PATH_SUBSCRIBE), any(Object.class)))
                .thenThrow(new IllegalStateException("subscribe down"));
        Order order = pedido(linea(1, 1000));

        assertThatCode(() -> service.createShipment(order)).doesNotThrowAnyException();
    }

    @Test
    void unaSuscripcionRechazadaTampocoAnulaElEnvio() {
        when(client.post(eq(PATH_CREATE), any(Object.class)))
                .thenReturn(ok("{\"success\":true,\"result\":{\"waybill_number\":\"YT-A\"}}"));
        when(client.post(eq(PATH_SUBSCRIBE), any(Object.class)))
                .thenReturn(ok("{\"success\":false,\"code\":\"1\",\"msg\":\"nope\"}"));

        assertThat(service.createShipment(pedido(linea(1, 1000))).fulfillmentRef()).isEqualTo("YT-A");
    }

    @Test
    void conElPushDesactivadoNoSeSuscribeNingunaGuia() {
        ReflectionTestUtils.setField(service, "trackingSubscriptionEnabled", false);

        service.subscribeTracking("YT-A");

        verify(client, never()).post(eq(PATH_SUBSCRIBE), any(Object.class));
    }

    // ── Aduana ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void seDeclaraElValorIntrinsecoDeLosBienesYNoElTotalDelPedido() {
        // Declarar de menos es infradeclaración; declarar el total (con envío/impuesto) hace que el
        // transportista liquide impuesto de más a cargo del comercio.
        OrderItem item = linea(1, 4500);
        ProductEntity producto = ProductEntity.builder().hsCode("610910").weightGrams(300).build();
        producto.setId(item.getProductId());
        when(productRepository.findById(item.getProductId())).thenReturn(Optional.of(producto));

        service.declarationFor(pedido(item));

        // El valor declarado es el de la mercancía, y el bulto que lo ampara lleva UNA partida arancelaria.
        verify(customsValuation).valuate("ES", 4500, 0, List.of(new DutyParcel(4500, 1)));
    }

    @Test
    void elIossViajaSoloCuandoElPedidoNoSuperaElUmbralDeMinimis() {
        ReflectionTestUtils.setField(service, "iossNumber", "IM2760000742");
        Order order = pedido(linea(1, 4500));

        YunExpressRequests.CreateShipment bajoUmbral = service.createPayload(order, ParcelSpec.ofWeight(500),
                "BPA", valoracion(false));
        YunExpressRequests.CreateShipment sobreUmbral = service.createPayload(order, ParcelSpec.ofWeight(500),
                "BPA", valoracion(true));

        assertThat(bajoUmbral.customsNumber()).isNotNull();
        assertThat(bajoUmbral.customsNumber().iossCode()).isEqualTo("IM2760000742");
        // Por encima de 150 € el régimen IOSS no aplica: mandarlo hace que la aduana rechace la liquidación.
        assertThat(sobreUmbral.customsNumber()).isNull();
    }

    @Test
    void sinIossConfiguradoElBloqueAduaneroNoSeMonta() {
        YunExpressRequests.CreateShipment payload = service.createPayload(pedido(linea(1, 4500)),
                ParcelSpec.ofWeight(500), "BPA", valoracion(false));

        assertThat(payload.customsNumber()).isNull();
    }

    @Test
    void elDestinatarioSeMontaConLaDireccionDelPedidoYElPaisEnMayusculas() {
        Order order = pedido(linea(1, 1000));
        order.setShippingCountry("es");
        order.setShippingLine2("  ");

        YunExpressRequests.CreateShipment payload = service.createPayload(order, ParcelSpec.ofWeight(500),
                "BPA", valoracion(false));

        assertThat(payload.receiver().countryCode()).isEqualTo("ES");
        assertThat(payload.receiver().firstName()).isEqualTo("Ana");
        assertThat(payload.receiver().lastName()).isEqualTo("López");
        // Una línea 2 en blanco no debe ocupar una línea de la etiqueta.
        assertThat(payload.receiver().addressLines()).containsExactly("Calle Mayor 1");
    }
}
