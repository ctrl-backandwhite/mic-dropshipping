package com.nexaplatform.dropshipping.infrastructure.integration.stripe;

import com.stripe.Stripe;
import com.stripe.model.Customer;
import com.stripe.model.Invoice;
import com.stripe.model.InvoiceCollection;
import com.stripe.model.InvoiceLineItem;
import com.stripe.model.InvoiceLineItemCollection;
import com.stripe.model.PaymentMethod;
import com.stripe.model.PaymentMethodCollection;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.SubscriptionItemCollection;
import com.stripe.model.checkout.Session;
import com.stripe.param.CustomerUpdateParams;
import com.stripe.param.InvoiceListParams;
import com.stripe.param.PaymentMethodListParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.checkout.SessionCreateParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Segunda tanda de {@link StripeService}: interruptor de configuración, checkout alojado, gestión de
 * la tarjeta guardada y ciclo de vida de la suscripción (cambio de plan y cancelación).
 *
 * <p>Todo el SDK es estático, así que cada test intercepta la clase concreta con {@link MockedStatic}.
 */
class Cov02StripeServiceTest {

    private static final String PLATFORM_ID = "nexadrop-dropshipping";
    private static final String PLATFORM_ENV = "test";

    private StripeService service;
    private String apiKeyPrevia;

    @BeforeEach
    void setUp() {
        apiKeyPrevia = Stripe.apiKey;
        service = new StripeService();
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "secretKey", "sk_test_123");
        ReflectionTestUtils.setField(service, "publishableKey", "pk_test_123");
        ReflectionTestUtils.setField(service, "webhookSecret", "whsec_1");
        ReflectionTestUtils.setField(service, "platformId", PLATFORM_ID);
        ReflectionTestUtils.setField(service, "platformEnv", PLATFORM_ENV);
    }

    @AfterEach
    void tearDown() {
        // Stripe.apiKey es global del SDK: se restaura para no contaminar al resto de la suite.
        Stripe.apiKey = apiKeyPrevia;
    }

    /* ============ interruptor de configuración ============ */

    @Test
    void sinClaveSecretaStripeQuedaDesactivadoAunqueEsteHabilitado() {
        // Un despliegue con la variable a medias no debe intentar cobrar contra Stripe.
        ReflectionTestUtils.setField(service, "secretKey", "   ");

        service.init();

        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void conClaveSecretaSeConfiguraElSdkYQuedaHabilitado() {
        service.init();

        assertThat(service.isEnabled()).isTrue();
        assertThat(Stripe.apiKey).isEqualTo("sk_test_123");
    }

    @Test
    void laClavePublicaNuncaEsNulaParaElFrontend() {
        assertThat(service.publishableKey()).isEqualTo("pk_test_123");

        ReflectionTestUtils.setField(service, "publishableKey", null);
        assertThat(service.publishableKey()).isEmpty();
    }

    @Test
    void elWebhookSoloSeConsideraConfiguradoSiHaySecreto() {
        assertThat(service.webhookConfigured()).isTrue();

        ReflectionTestUtils.setField(service, "webhookSecret", "  ");
        assertThat(service.webhookConfigured()).isFalse();
    }

    /* ============ checkout alojado ============ */

    @Test
    void elCheckoutAlojadoMarcaElCobroComoDePlanYDevuelveElIdDeSesion() throws Exception {
        Session sesion = mock(Session.class);
        ArgumentCaptor<SessionCreateParams> captor = ArgumentCaptor.forClass(SessionCreateParams.class);
        try (MockedStatic<Session> s = mockStatic(Session.class)) {
            s.when(() -> Session.create(any(SessionCreateParams.class))).thenReturn(sesion);

            Session resultado = service.createCheckoutSession("cliente@nx.com", "price_1",
                    "https://nx036.com/ok", "https://nx036.com/ko");

            assertThat(resultado).isSameAs(sesion);
            s.verify(() -> Session.create(captor.capture()));
            Map<String, Object> raw = captor.getValue().toMap();
            assertThat(raw).containsEntry("mode", "subscription")
                    .containsEntry("customer_email", "cliente@nx.com")
                    .containsEntry("cancel_url", "https://nx036.com/ko");
            // Sin el marcador de sesión la vuelta del pago no podría confirmarse contra Stripe.
            assertThat(raw.get("success_url")).asString().endsWith("?session_id={CHECKOUT_SESSION_ID}");
            @SuppressWarnings("unchecked")
            Map<String, Object> md = (Map<String, Object>) raw.get("metadata");
            // El dashboard separa el ingreso de PLANES del de productos por este metadato.
            assertThat(md).containsEntry("purpose", "subscription").containsEntry("platform", PLATFORM_ID);
        }
    }

    /* ============ tarjeta guardada ============ */

    @Test
    void fijarLaTarjetaPorDefectoLaEscribeEnLosAjustesDeFacturacion() throws Exception {
        Customer cliente = mock(Customer.class);
        ArgumentCaptor<CustomerUpdateParams> captor = ArgumentCaptor.forClass(CustomerUpdateParams.class);
        try (MockedStatic<Customer> c = mockStatic(Customer.class)) {
            c.when(() -> Customer.retrieve("cus_1")).thenReturn(cliente);

            service.setDefaultPaymentMethod("cus_1", "pm_9");

            verify(cliente).update(captor.capture());
            @SuppressWarnings("unchecked")
            Map<String, Object> ajustes = (Map<String, Object>) captor.getValue().toMap().get("invoice_settings");
            assertThat(ajustes).containsEntry("default_payment_method", "pm_9");
        }
    }

    @Test
    void desvincularUnaTarjetaLaDesconectaDelCliente() throws Exception {
        PaymentMethod tarjeta = mock(PaymentMethod.class);
        try (MockedStatic<PaymentMethod> pm = mockStatic(PaymentMethod.class)) {
            pm.when(() -> PaymentMethod.retrieve("pm_9")).thenReturn(tarjeta);

            service.detachPaymentMethod("pm_9");

            verify(tarjeta).detach();
        }
    }

    @Test
    void sinAjustesDeFacturacionNoHayTarjetaPorDefecto() throws Exception {
        Customer cliente = mock(Customer.class);
        when(cliente.getInvoiceSettings()).thenReturn(null);
        try (MockedStatic<Customer> c = mockStatic(Customer.class)) {
            c.when(() -> Customer.retrieve("cus_1")).thenReturn(cliente);

            assertThat(service.defaultPaymentMethodId("cus_1")).isNull();
        }
    }

    @Test
    void siHayTarjetaPorDefectoEsLaQueCobra() throws Exception {
        Customer cliente = mock(Customer.class);
        Customer.InvoiceSettings ajustes = new Customer.InvoiceSettings();
        ajustes.setDefaultPaymentMethod("pm_default");
        when(cliente.getInvoiceSettings()).thenReturn(ajustes);
        try (MockedStatic<Customer> c = mockStatic(Customer.class)) {
            c.when(() -> Customer.retrieve("cus_1")).thenReturn(cliente);

            assertThat(service.defaultOrFirstCardId("cus_1")).isEqualTo("pm_default");
        }
    }

    @Test
    void sinTarjetaPorDefectoSeCobraLaPrimeraGuardada() throws Exception {
        // El usuario guardó tarjeta pero nunca marcó cuál es la principal: la suscripción no puede
        // quedarse sin medio de cobro por ese detalle.
        Customer cliente = mock(Customer.class);
        Customer.InvoiceSettings ajustes = new Customer.InvoiceSettings();
        ajustes.setDefaultPaymentMethod("   ");
        when(cliente.getInvoiceSettings()).thenReturn(ajustes);
        PaymentMethod primera = mock(PaymentMethod.class);
        when(primera.getId()).thenReturn("pm_primera");
        PaymentMethodCollection coleccion = mock(PaymentMethodCollection.class);
        when(coleccion.getData()).thenReturn(List.of(primera));
        try (MockedStatic<Customer> c = mockStatic(Customer.class);
                MockedStatic<PaymentMethod> pm = mockStatic(PaymentMethod.class)) {
            c.when(() -> Customer.retrieve("cus_1")).thenReturn(cliente);
            pm.when(() -> PaymentMethod.list(any(PaymentMethodListParams.class))).thenReturn(coleccion);

            assertThat(service.defaultOrFirstCardId("cus_1")).isEqualTo("pm_primera");
        }
    }

    @Test
    void sinNingunaTarjetaGuardadaNoHayMedioDeCobro() throws Exception {
        Customer cliente = mock(Customer.class);
        when(cliente.getInvoiceSettings()).thenReturn(null);
        PaymentMethodCollection vacia = mock(PaymentMethodCollection.class);
        when(vacia.getData()).thenReturn(List.of());
        try (MockedStatic<Customer> c = mockStatic(Customer.class);
                MockedStatic<PaymentMethod> pm = mockStatic(PaymentMethod.class)) {
            c.when(() -> Customer.retrieve("cus_1")).thenReturn(cliente);
            pm.when(() -> PaymentMethod.list(any(PaymentMethodListParams.class))).thenReturn(vacia);

            assertThat(service.defaultOrFirstCardId("cus_1")).isNull();
        }
    }

    /* ============ ciclo de vida de la suscripción ============ */

    @Test
    void cambiarDePlanSustituyeLaLineaExistenteConProrrateo() throws Exception {
        // Añadir una línea nueva en vez de reemplazar la actual cobraría los dos planes a la vez.
        SubscriptionItem linea = mock(SubscriptionItem.class);
        when(linea.getId()).thenReturn("si_1");
        SubscriptionItemCollection lineas = mock(SubscriptionItemCollection.class);
        when(lineas.getData()).thenReturn(List.of(linea));
        Subscription actual = mock(Subscription.class);
        when(actual.getItems()).thenReturn(lineas);
        Subscription actualizada = mock(Subscription.class);
        when(actualizada.getId()).thenReturn("sub_1");
        when(actualizada.getStatus()).thenReturn("active");
        when(actual.update(any(SubscriptionUpdateParams.class))).thenReturn(actualizada);
        ArgumentCaptor<SubscriptionUpdateParams> captor = ArgumentCaptor.forClass(SubscriptionUpdateParams.class);
        try (MockedStatic<Subscription> s = mockStatic(Subscription.class)) {
            s.when(() -> Subscription.retrieve("sub_1")).thenReturn(actual);

            StripeService.SubResult resultado = service.changeSubscriptionPrice("sub_1", "price_2", "PRO",
                    SubscriptionUpdateParams.ProrationBehavior.ALWAYS_INVOICE);

            assertThat(resultado.id()).isEqualTo("sub_1");
            assertThat(resultado.status()).isEqualTo("active");
            verify(actual).update(captor.capture());
            Map<String, Object> raw = captor.getValue().toMap();
            assertThat(raw).containsEntry("proration_behavior", "always_invoice");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) raw.get("items");
            assertThat(items).hasSize(1);
            assertThat(items.get(0)).containsEntry("id", "si_1").containsEntry("price", "price_2");
        }
    }

    @Test
    void cancelarAlFinalDelPeriodoNoCortaElServicioYaPagado() throws Exception {
        Subscription actual = mock(Subscription.class);
        Subscription actualizada = mock(Subscription.class);
        when(actualizada.getId()).thenReturn("sub_1");
        when(actualizada.getStatus()).thenReturn("active");
        when(actual.update(any(SubscriptionUpdateParams.class))).thenReturn(actualizada);
        ArgumentCaptor<SubscriptionUpdateParams> captor = ArgumentCaptor.forClass(SubscriptionUpdateParams.class);
        try (MockedStatic<Subscription> s = mockStatic(Subscription.class)) {
            s.when(() -> Subscription.retrieve("sub_1")).thenReturn(actual);

            StripeService.SubResult resultado = service.cancelSubscription("sub_1", true);

            assertThat(resultado.status()).isEqualTo("active");
            verify(actual).update(captor.capture());
            assertThat(captor.getValue().toMap()).containsEntry("cancel_at_period_end", Boolean.TRUE);
            verify(actual, never()).cancel();
        }
    }

    @Test
    void cancelarDeInmediatoCortaLaSuscripcionEnElActo() throws Exception {
        Subscription actual = mock(Subscription.class);
        Subscription cancelada = mock(Subscription.class);
        when(cancelada.getId()).thenReturn("sub_1");
        when(cancelada.getStatus()).thenReturn("canceled");
        when(actual.cancel()).thenReturn(cancelada);
        try (MockedStatic<Subscription> s = mockStatic(Subscription.class)) {
            s.when(() -> Subscription.retrieve("sub_1")).thenReturn(actual);

            StripeService.SubResult resultado = service.cancelSubscription("sub_1", false);

            assertThat(resultado.status()).isEqualTo("canceled");
            verify(actual).cancel();
        }
    }

    /* ============ facturas ============ */

    @Test
    void laFacturaExtraeDescripcionYPeriodoDeSuPrimeraLinea() throws Exception {
        InvoiceLineItem.Period periodo = new InvoiceLineItem.Period();
        periodo.setStart(1000L);
        periodo.setEnd(2000L);
        InvoiceLineItem linea = mock(InvoiceLineItem.class);
        when(linea.getDescription()).thenReturn("Plan PRO — MONTHLY");
        when(linea.getPeriod()).thenReturn(periodo);
        InvoiceLineItemCollection lineas = mock(InvoiceLineItemCollection.class);
        when(lineas.getData()).thenReturn(List.of(linea));
        Invoice factura = mock(Invoice.class);
        when(factura.getLines()).thenReturn(lineas);
        when(factura.getSubtotal()).thenReturn(2000L);
        when(factura.getTax()).thenReturn(420L);
        when(factura.getCustomerName()).thenReturn("Ana");
        when(factura.getCustomerEmail()).thenReturn("ana@nx.com");
        InvoiceCollection coleccion = mock(InvoiceCollection.class);
        when(coleccion.getData()).thenReturn(List.of(factura));
        try (MockedStatic<Invoice> inv = mockStatic(Invoice.class)) {
            inv.when(() -> Invoice.list(any(InvoiceListParams.class))).thenReturn(coleccion);

            StripeService.InvoiceInfo info = service.listInvoices("cus_1", 5).get(0);

            assertThat(info.lineDescription()).isEqualTo("Plan PRO — MONTHLY");
            assertThat(info.periodStart()).isEqualTo(1000L);
            assertThat(info.periodEnd()).isEqualTo(2000L);
            assertThat(info.subtotal()).isEqualTo(2000L);
            assertThat(info.tax()).isEqualTo(420L);
            assertThat(info.customerName()).isEqualTo("Ana");
            assertThat(info.customerEmail()).isEqualTo("ana@nx.com");
        }
    }

    @Test
    void unaFacturaSinLineasNiImportesNoRompeElHistorial() throws Exception {
        // Las facturas de importe 0 (prueba gratuita) llegan con subtotal/impuesto nulos.
        Invoice factura = mock(Invoice.class);
        when(factura.getLines()).thenReturn(null);
        when(factura.getSubtotal()).thenReturn(null);
        when(factura.getTax()).thenReturn(null);
        InvoiceCollection coleccion = mock(InvoiceCollection.class);
        when(coleccion.getData()).thenReturn(List.of(factura));
        try (MockedStatic<Invoice> inv = mockStatic(Invoice.class)) {
            inv.when(() -> Invoice.list(any(InvoiceListParams.class))).thenReturn(coleccion);

            List<StripeService.InvoiceInfo> infos = service.listInvoices("cus_1", 5);

            assertThat(infos).hasSize(1);
            assertThat(infos.get(0).subtotal()).isZero();
            assertThat(infos.get(0).tax()).isZero();
            assertThat(infos.get(0).lineDescription()).isNull();
            assertThat(infos.get(0).periodStart()).isNull();
        }
    }

    @Test
    void elLimiteDeFacturasNuncaBajaDeUna() throws Exception {
        // Pedir "0 facturas" a Stripe es un error de parámetro: se sube a 1 antes de llamar.
        InvoiceCollection coleccion = mock(InvoiceCollection.class);
        when(coleccion.getData()).thenReturn(List.of());
        ArgumentCaptor<InvoiceListParams> captor = ArgumentCaptor.forClass(InvoiceListParams.class);
        try (MockedStatic<Invoice> inv = mockStatic(Invoice.class)) {
            inv.when(() -> Invoice.list(any(InvoiceListParams.class))).thenReturn(coleccion);

            service.listInvoices("cus_1", 0);

            inv.verify(() -> Invoice.list(captor.capture()));
            assertThat(captor.getValue().toMap()).containsEntry("limit", 1L);
        }
    }
}
