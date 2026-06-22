package com.nexaplatform.dropshipping.infrastructure.integration.stripe;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.InvoiceCollection;
import com.stripe.model.PaymentMethod;
import com.stripe.model.PaymentMethodCollection;
import com.stripe.model.Price;
import com.stripe.model.PriceCollection;
import com.stripe.model.SetupIntent;
import com.stripe.model.Subscription;
import com.stripe.model.TaxRate;
import com.stripe.model.TaxRateCollection;
import com.stripe.net.Webhook;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.InvoiceListParams;
import com.stripe.param.PaymentMethodListParams;
import com.stripe.param.PriceCreateParams;
import com.stripe.param.PriceListParams;
import com.stripe.param.SetupIntentCreateParams;
import com.stripe.param.SubscriptionCreateParams;
import com.stripe.param.TaxRateCreateParams;
import com.stripe.param.TaxRateListParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Tests de camino feliz de {@link StripeService}. El servicio no tiene dependencias de constructor:
 * todas las clases del SDK exponen create/retrieve/list estáticos, por lo que se interceptan con
 * {@link MockedStatic} dentro de cada test. Las propiedades @Value se inyectan con ReflectionTestUtils.
 */
class StripeServiceTest {

    private static final String PLATFORM_ID = "nexadrop-dropshipping";
    private static final String PLATFORM_ENV = "test";
    private static final String WEBHOOK_SECRET = "whsec_test_123";

    private StripeService service;

    @BeforeEach
    void setUp() {
        service = new StripeService();
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "secretKey", "sk_test_123");
        ReflectionTestUtils.setField(service, "publishableKey", "pk_test_123");
        ReflectionTestUtils.setField(service, "webhookSecret", WEBHOOK_SECRET);
        ReflectionTestUtils.setField(service, "platformId", PLATFORM_ID);
        ReflectionTestUtils.setField(service, "platformEnv", PLATFORM_ENV);
    }

    @Test
    void getOrCreateCustomer_retrievesWhenIdProvided() throws Exception {
        Customer existing = mock(Customer.class);
        try (MockedStatic<Customer> cust = mockStatic(Customer.class)) {
            cust.when(() -> Customer.retrieve("cus_existing")).thenReturn(existing);

            Customer result = service.getOrCreateCustomer("cus_existing", "a@b.com", "user-1");

            assertThat(result).isSameAs(existing);
        }
    }

    @Test
    void getOrCreateCustomer_createsWithPlatformAndUserMetadata() throws Exception {
        Customer created = mock(Customer.class);
        ArgumentCaptor<CustomerCreateParams> captor = ArgumentCaptor.forClass(CustomerCreateParams.class);
        try (MockedStatic<Customer> cust = mockStatic(Customer.class)) {
            cust.when(() -> Customer.create(any(CustomerCreateParams.class))).thenReturn(created);

            Customer result = service.getOrCreateCustomer(null, "buyer@nx.com", "user-42");

            assertThat(result).isSameAs(created);
            cust.verify(() -> Customer.create(captor.capture()));
            Map<String, Object> raw = captor.getValue().toMap();
            assertThat(raw.get("email")).isEqualTo("buyer@nx.com");
            @SuppressWarnings("unchecked")
            Map<String, Object> md = (Map<String, Object>) raw.get("metadata");
            assertThat(md).containsEntry("platform", PLATFORM_ID)
                    .containsEntry("env", PLATFORM_ENV)
                    .containsEntry("user_id", "user-42");
        }
    }

    @Test
    void createSetupIntent_buildsCardSetupIntentForCustomer() throws Exception {
        SetupIntent intent = mock(SetupIntent.class);
        ArgumentCaptor<SetupIntentCreateParams> captor = ArgumentCaptor.forClass(SetupIntentCreateParams.class);
        try (MockedStatic<SetupIntent> si = mockStatic(SetupIntent.class)) {
            si.when(() -> SetupIntent.create(any(SetupIntentCreateParams.class))).thenReturn(intent);

            SetupIntent result = service.createSetupIntent("cus_1");

            assertThat(result).isSameAs(intent);
            si.verify(() -> SetupIntent.create(captor.capture()));
            Map<String, Object> raw = captor.getValue().toMap();
            assertThat(raw.get("customer")).isEqualTo("cus_1");
            assertThat(raw.get("payment_method_types")).asInstanceOf(
                    org.assertj.core.api.InstanceOfAssertFactories.list(String.class)).contains("card");
        }
    }

    @Test
    void listCards_returnsCollectionData() throws Exception {
        PaymentMethod card = mock(PaymentMethod.class);
        PaymentMethodCollection collection = mock(PaymentMethodCollection.class);
        when(collection.getData()).thenReturn(List.of(card));
        ArgumentCaptor<PaymentMethodListParams> captor = ArgumentCaptor.forClass(PaymentMethodListParams.class);
        try (MockedStatic<PaymentMethod> pm = mockStatic(PaymentMethod.class)) {
            pm.when(() -> PaymentMethod.list(any(PaymentMethodListParams.class))).thenReturn(collection);

            List<PaymentMethod> result = service.listCards("cus_9");

            assertThat(result).containsExactly(card);
            pm.verify(() -> PaymentMethod.list(captor.capture()));
            Map<String, Object> raw = captor.getValue().toMap();
            assertThat(raw.get("customer")).isEqualTo("cus_9");
            assertThat(raw.get("type")).isEqualTo("card");
        }
    }

    @Test
    void ensureRecurringPrice_reusesExistingPriceByLookupKey() throws Exception {
        Price existing = mock(Price.class);
        when(existing.getId()).thenReturn("price_existing");
        PriceCollection collection = mock(PriceCollection.class);
        when(collection.getData()).thenReturn(List.of(existing));
        ArgumentCaptor<PriceListParams> captor = ArgumentCaptor.forClass(PriceListParams.class);
        try (MockedStatic<Price> price = mockStatic(Price.class)) {
            price.when(() -> Price.list(any(PriceListParams.class))).thenReturn(collection);

            String id = service.ensureRecurringPrice("PRO", "MONTHLY", 1999, "USD", "Pro Plan");

            assertThat(id).isEqualTo("price_existing");
            price.verify(() -> Price.list(captor.capture()));
            Map<String, Object> raw = captor.getValue().toMap();
            assertThat(raw.get("lookup_keys")).asInstanceOf(
                            org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                    .containsExactly("nx_pro_monthly_1999_usd");
        }
    }

    @Test
    void ensureRecurringPrice_createsYearlyPriceWhenNoneFound() throws Exception {
        PriceCollection empty = mock(PriceCollection.class);
        when(empty.getData()).thenReturn(List.of());
        Price created = mock(Price.class);
        when(created.getId()).thenReturn("price_new");
        ArgumentCaptor<PriceCreateParams> captor = ArgumentCaptor.forClass(PriceCreateParams.class);
        try (MockedStatic<Price> price = mockStatic(Price.class)) {
            price.when(() -> Price.list(any(PriceListParams.class))).thenReturn(empty);
            price.when(() -> Price.create(any(PriceCreateParams.class))).thenReturn(created);

            String id = service.ensureRecurringPrice("PRO", "YEARLY", 19990, "EUR", "Pro Plan");

            assertThat(id).isEqualTo("price_new");
            price.verify(() -> Price.create(captor.capture()));
            Map<String, Object> raw = captor.getValue().toMap();
            assertThat(raw.get("currency")).isEqualTo("eur");
            assertThat(raw.get("unit_amount")).isEqualTo(19990L);
            assertThat(raw.get("lookup_key")).isEqualTo("nx_pro_yearly_19990_eur");
            @SuppressWarnings("unchecked")
            Map<String, Object> recurring = (Map<String, Object>) raw.get("recurring");
            assertThat(recurring.get("interval")).isEqualTo("year");
        }
    }

    @Test
    void ensureTaxRate_returnsNullWhenBpsNonPositive() throws Exception {
        assertThat(service.ensureTaxRate("ES", 0)).isNull();
        assertThat(service.ensureTaxRate("ES", -5)).isNull();
    }

    @Test
    void ensureTaxRate_reusesExistingByMetadataTag() throws Exception {
        TaxRate match = mock(TaxRate.class);
        when(match.getId()).thenReturn("txr_existing");
        when(match.getMetadata()).thenReturn(Map.of("nx_tag", "nx_iva_es_2100"));
        TaxRateCollection collection = mock(TaxRateCollection.class);
        when(collection.getData()).thenReturn(List.of(match));
        try (MockedStatic<TaxRate> tax = mockStatic(TaxRate.class)) {
            tax.when(() -> TaxRate.list(any(TaxRateListParams.class))).thenReturn(collection);

            String id = service.ensureTaxRate("ES", 2100);

            assertThat(id).isEqualTo("txr_existing");
        }
    }

    @Test
    void ensureTaxRate_createsWhenNotFound() throws Exception {
        TaxRateCollection collection = mock(TaxRateCollection.class);
        when(collection.getData()).thenReturn(List.of());
        TaxRate created = mock(TaxRate.class);
        when(created.getId()).thenReturn("txr_new");
        ArgumentCaptor<TaxRateCreateParams> captor = ArgumentCaptor.forClass(TaxRateCreateParams.class);
        try (MockedStatic<TaxRate> tax = mockStatic(TaxRate.class)) {
            tax.when(() -> TaxRate.list(any(TaxRateListParams.class))).thenReturn(collection);
            tax.when(() -> TaxRate.create(any(TaxRateCreateParams.class))).thenReturn(created);

            String id = service.ensureTaxRate("ES", 2100);

            assertThat(id).isEqualTo("txr_new");
            tax.verify(() -> TaxRate.create(captor.capture()));
            Map<String, Object> raw = captor.getValue().toMap();
            assertThat(raw.get("country")).isEqualTo("ES");
            assertThat(raw.get("inclusive")).isEqualTo(Boolean.FALSE);
            assertThat(raw.get("percentage")).isEqualTo(new java.math.BigDecimal("21.00"));
            @SuppressWarnings("unchecked")
            Map<String, Object> md = (Map<String, Object>) raw.get("metadata");
            assertThat(md).containsEntry("nx_tag", "nx_iva_es_2100").containsEntry("platform", PLATFORM_ID);
        }
    }

    @Test
    void createSubscription_errorIfIncompleteWithTaxRateAndMetadata() throws Exception {
        Subscription sub = mock(Subscription.class);
        when(sub.getId()).thenReturn("sub_1");
        when(sub.getStatus()).thenReturn("active");
        when(sub.getCurrentPeriodStart()).thenReturn(1000L);
        when(sub.getCurrentPeriodEnd()).thenReturn(2000L);
        ArgumentCaptor<SubscriptionCreateParams> captor = ArgumentCaptor.forClass(SubscriptionCreateParams.class);
        try (MockedStatic<Subscription> s = mockStatic(Subscription.class)) {
            s.when(() -> Subscription.create(any(SubscriptionCreateParams.class))).thenReturn(sub);

            StripeService.SubResult result = service.createSubscription("cus_1", "price_1", "pm_1",
                    "txr_1", "PRO", "user-7", "localsub-9");

            assertThat(result.id()).isEqualTo("sub_1");
            assertThat(result.status()).isEqualTo("active");
            assertThat(result.periodStart()).isEqualTo(1000L);
            assertThat(result.periodEnd()).isEqualTo(2000L);

            s.verify(() -> Subscription.create(captor.capture()));
            Map<String, Object> raw = captor.getValue().toMap();
            assertThat(raw.get("customer")).isEqualTo("cus_1");
            assertThat(raw.get("payment_behavior")).isEqualTo("error_if_incomplete");
            assertThat(raw.get("default_payment_method")).isEqualTo("pm_1");
            assertThat(raw.get("default_tax_rates")).asInstanceOf(
                    org.assertj.core.api.InstanceOfAssertFactories.list(String.class)).contains("txr_1");
            @SuppressWarnings("unchecked")
            Map<String, Object> md = (Map<String, Object>) raw.get("metadata");
            assertThat(md).containsEntry("purpose", "subscription")
                    .containsEntry("plan_code", "PRO")
                    .containsEntry("user_id", "user-7")
                    .containsEntry("subscription_id", "localsub-9")
                    .containsEntry("platform", PLATFORM_ID);
        }
    }

    @Test
    void listInvoices_mapsToInvoiceInfo() throws Exception {
        Invoice inv = mock(Invoice.class);
        when(inv.getNumber()).thenReturn("INV-001");
        when(inv.getTotal()).thenReturn(2599L);
        when(inv.getCurrency()).thenReturn("usd");
        when(inv.getStatus()).thenReturn("paid");
        when(inv.getCreated()).thenReturn(1700000000L);
        when(inv.getInvoicePdf()).thenReturn("https://pdf");
        when(inv.getHostedInvoiceUrl()).thenReturn("https://hosted");
        InvoiceCollection collection = mock(InvoiceCollection.class);
        when(collection.getData()).thenReturn(List.of(inv));
        ArgumentCaptor<InvoiceListParams> captor = ArgumentCaptor.forClass(InvoiceListParams.class);
        try (MockedStatic<Invoice> invoices = mockStatic(Invoice.class)) {
            invoices.when(() -> Invoice.list(any(InvoiceListParams.class))).thenReturn(collection);

            List<StripeService.InvoiceInfo> result = service.listInvoices("cus_5", 3);

            assertThat(result).hasSize(1);
            StripeService.InvoiceInfo info = result.get(0);
            assertThat(info.number()).isEqualTo("INV-001");
            assertThat(info.total()).isEqualTo(2599L);
            assertThat(info.currency()).isEqualTo("usd");
            assertThat(info.status()).isEqualTo("paid");
            assertThat(info.pdfUrl()).isEqualTo("https://pdf");
            assertThat(info.hostedUrl()).isEqualTo("https://hosted");
            invoices.verify(() -> Invoice.list(captor.capture()));
            Map<String, Object> raw = captor.getValue().toMap();
            assertThat(raw.get("customer")).isEqualTo("cus_5");
            assertThat(raw.get("limit")).isEqualTo(3L);
        }
    }

    @Test
    void constructWebhookEvent_returnsEventOnValidSignature() throws Exception {
        Event event = mock(Event.class);
        try (MockedStatic<Webhook> wh = mockStatic(Webhook.class)) {
            wh.when(() -> Webhook.constructEvent("payload", "sig", WEBHOOK_SECRET)).thenReturn(event);

            Event result = service.constructWebhookEvent("payload", "sig");

            assertThat(result).isSameAs(event);
        }
    }

    @Test
    void constructWebhookEvent_propagatesSignatureException() {
        try (MockedStatic<Webhook> wh = mockStatic(Webhook.class)) {
            wh.when(() -> Webhook.constructEvent(anyString(), anyString(), eq(WEBHOOK_SECRET)))
                    .thenThrow(new SignatureVerificationException("bad sig", "sig"));

            assertThatThrownBy(() -> service.constructWebhookEvent("payload", "bad"))
                    .isInstanceOf(SignatureVerificationException.class);
        }
    }
}
