package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.mapper.CustomerSubscriptionUpdateMapper;
import com.nexaplatform.dropshipping.application.service.CountryTaxService;
import com.nexaplatform.dropshipping.application.service.InvoiceService;
import com.nexaplatform.dropshipping.application.service.SubscriptionNotificationService;
import com.nexaplatform.dropshipping.application.usecase.CustomerSubscriptionUseCase;
import com.nexaplatform.dropshipping.application.usecase.SubscriptionPlanUseCase;
import com.nexaplatform.dropshipping.application.usecase.impl.CustomerSubscriptionUseCaseImpl;
import com.nexaplatform.dropshipping.domain.enums.SubscriptionStatus;
import com.nexaplatform.dropshipping.domain.model.CustomerSubscription;
import com.nexaplatform.dropshipping.domain.repository.CustomerSubscriptionRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.integration.stripe.StripeService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SubscriptionPlanEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SubscriptionPlanRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import com.stripe.model.Customer;
import com.stripe.model.PaymentMethod;
import com.stripe.model.SetupIntent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Reglas de facturación de {@link CustomerSubscriptionUseCaseImpl} que no cubría el test existente:
 * la prueba gratis de un mes (un solo uso), su vencimiento por barrido, la vista normalizada de admin,
 * la propiedad de las tarjetas/facturas frente a Stripe y la moneda con la que se cobra el plan.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov07CustomerSubscriptionBillingTest {

    @Mock
    CustomerSubscriptionRepository customerSubscriptionRepository;
    @Mock
    CustomerSubscriptionUpdateMapper customerSubscriptionUpdateMapper;
    @Mock
    SubscriptionPlanRepository planRepository;
    @Mock
    SubscriptionPlanUseCase subscriptionPlanUseCase;
    @Mock
    StripeService stripeService;
    @Mock
    UserRepository userRepository;
    @Mock
    CurrencyRateService currencyService;
    @Mock
    CountryTaxService countryTaxService;
    @Mock
    InvoiceService invoiceService;
    @Mock
    SubscriptionNotificationService subscriptionNotificationService;

    @InjectMocks
    CustomerSubscriptionUseCaseImpl useCase;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        CurrencyHolder.clear();
    }

    @AfterEach
    void tearDown() {
        // El código de cobro lee la divisa activa de un ThreadLocal: si no se limpia, contamina otros tests.
        CurrencyHolder.clear();
    }

    /* ==================== Vista de admin ==================== */

    @Test
    void unPlanGratisNuncaSeMuestraEnPruebaEnElListadoDeAdmin() {
        CustomerSubscription free = CustomerSubscription.builder().planCode("FREE").status(SubscriptionStatus.TRIALING)
                .trialEndsAt(Instant.now()).billingPeriod("MONTH").build();
        when(customerSubscriptionRepository.findAll()).thenReturn(List.of(free));

        // Se pide el filtro ACTIVE: la normalización ocurre ANTES de filtrar, así que la fila FREE entra.
        List<CustomerSubscription> result = useCase.listAdminSubscriptions("ACTIVE");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(result.get(0).getTrialEndsAt()).isNull();
    }

    @Test
    void unPlanSinPrecioTambienCuentaComoGratisAunqueSuCodigoNoSeaFree() {
        CustomerSubscription free = CustomerSubscription.builder().planCode("STARTER").priceMonthly(0).priceYearly(0)
                .status(SubscriptionStatus.TRIALING).build();
        when(customerSubscriptionRepository.findAll()).thenReturn(List.of(free));

        assertThat(useCase.listAdminSubscriptions(null).get(0).getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void unPlanDePagoEnPruebaConservaSuEstadoTrialing() {
        CustomerSubscription paid = CustomerSubscription.builder().planCode("PRO").priceMonthly(999)
                .status(SubscriptionStatus.TRIALING).build();
        when(customerSubscriptionRepository.findAll()).thenReturn(List.of(paid));

        assertThat(useCase.listAdminSubscriptions(null).get(0).getStatus()).isEqualTo(SubscriptionStatus.TRIALING);
    }

    @Test
    void elPeriodoLegacySeCanonicalizaAMonthlyYYearly() {
        CustomerSubscription month = CustomerSubscription.builder().planCode("PRO").priceMonthly(1)
                .status(SubscriptionStatus.ACTIVE).billingPeriod("month").build();
        CustomerSubscription year = month.withBillingPeriod("YEAR");
        CustomerSubscription other = month.withBillingPeriod("trimestral");
        CustomerSubscription none = month.withBillingPeriod(null);
        when(customerSubscriptionRepository.findAll()).thenReturn(List.of(month, year, other, none));

        List<CustomerSubscription> result = useCase.listAdminSubscriptions(null);

        // El front traduce el periodo por i18n: un valor crudo del enum se mostraría sin traducir.
        assertThat(result).extracting(CustomerSubscription::getBillingPeriod).containsExactly("MONTHLY", "YEARLY",
                "TRIMESTRAL", null);
    }

    /* ==================== Prueba gratis ==================== */

    @Test
    void elPlanGratisArrancaUnaPruebaDeQuinceDiasYMarcaLaCuenta() {
        SubscriptionPlanEntity plan = plan("FREE", 0, 0);
        UserEntity user = user(false);
        when(planRepository.findByCode("FREE")).thenReturn(Optional.of(plan));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(customerSubscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Instant before = Instant.now();
        CustomerSubscription saved = useCase.createSubscription(userId, "FREE", "YEARLY");

        // El modelo trata FREE como ACTIVE (no TRIALING) y siempre MENSUAL, ignorando el periodo pedido.
        assertThat(saved.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(saved.getBillingPeriod()).isEqualTo("MONTHLY");
        assertThat(saved.getCurrentPeriodEnd()).isAfterOrEqualTo(before.plus(15, ChronoUnit.DAYS).minusSeconds(5))
                .isBefore(before.plus(16, ChronoUnit.DAYS));
        assertThat(user.isFreeTrialUsed()).isTrue();
        verify(userRepository).save(user);
    }

    @Test
    void laSegundaPruebaGratisDeLaMismaCuentaSeRechaza() {
        when(planRepository.findByCode("FREE")).thenReturn(Optional.of(plan("FREE", 0, 0)));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(true)));

        assertThatThrownBy(() -> useCase.createSubscription(userId, "FREE", "MONTHLY"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("15 días");
        verify(customerSubscriptionRepository, never()).save(any());
    }

    @Test
    void unPlanSinCosteMensualNiAnualSeTrataComoPruebaGratisAunqueNoSeLlameFree() {
        SubscriptionPlanEntity plan = plan("STARTER", 0, 0);
        when(planRepository.findByCode("STARTER")).thenReturn(Optional.of(plan));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(true)));

        assertThatThrownBy(() -> useCase.createSubscription(userId, "STARTER", "MONTHLY"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void elPlanDePagoSinPeriodoSeContrataMensualPorTreintaDias() {
        when(planRepository.findByCode("PRO")).thenReturn(Optional.of(plan("PRO", 999, 9999)));
        when(customerSubscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CustomerSubscription saved = useCase.createSubscription(userId, "PRO", null);

        assertThat(saved.getBillingPeriod()).isEqualTo("MONTHLY");
        long days = ChronoUnit.DAYS.between(saved.getCurrentPeriodStart(), saved.getCurrentPeriodEnd());
        assertThat(days).isEqualTo(30);
        // El plan de pago NO consume la prueba gratis: ni se toca al usuario.
        verifyNoInteractions(userRepository);
    }

    @Test
    void elPlanAnualDePagoDuraTrescientosSesentaYCincoDias() {
        when(planRepository.findByCode("PRO")).thenReturn(Optional.of(plan("PRO", 999, 9999)));
        when(customerSubscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CustomerSubscription saved = useCase.createSubscription(userId, "PRO", "yearly");

        assertThat(saved.getBillingPeriod()).isEqualTo("YEARLY");
        assertThat(ChronoUnit.DAYS.between(saved.getCurrentPeriodStart(), saved.getCurrentPeriodEnd())).isEqualTo(365);
    }

    @Test
    void elBarridoCancelaSoloLasPruebasGratisVencidasYNoGestionadasPorStripe() {
        Instant past = Instant.now().minus(2, ChronoUnit.DAYS);
        Instant future = Instant.now().plus(2, ChronoUnit.DAYS);
        CustomerSubscription vencida = CustomerSubscription.builder().id(UUID.randomUUID()).planCode("FREE")
                .status(SubscriptionStatus.ACTIVE).currentPeriodEnd(past).build();
        CustomerSubscription vigente = CustomerSubscription.builder().id(UUID.randomUUID()).planCode("FREE")
                .status(SubscriptionStatus.ACTIVE).currentPeriodEnd(future).build();
        CustomerSubscription dePago = CustomerSubscription.builder().id(UUID.randomUUID()).planCode("PRO")
                .priceMonthly(999).status(SubscriptionStatus.ACTIVE).currentPeriodEnd(past).build();
        CustomerSubscription enStripe = CustomerSubscription.builder().id(UUID.randomUUID()).planCode("FREE")
                .status(SubscriptionStatus.ACTIVE).currentPeriodEnd(past).stripeSubscriptionId("sub_1").build();
        CustomerSubscription yaCancelada = CustomerSubscription.builder().id(UUID.randomUUID()).planCode("FREE")
                .status(SubscriptionStatus.CANCELED).currentPeriodEnd(past).build();
        when(customerSubscriptionRepository.findAll())
                .thenReturn(List.of(vencida, vigente, dePago, enStripe, yaCancelada));

        useCase.expireFreeTrials();

        ArgumentCaptor<CustomerSubscription> captor = ArgumentCaptor.forClass(CustomerSubscription.class);
        verify(customerSubscriptionRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(vencida.getId());
        assertThat(captor.getValue().getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(captor.getValue().getCanceledAt()).isNotNull();
    }

    @Test
    void elBarridoNoTocaLasPruebasSinFechaDeFin() {
        CustomerSubscription sinFin = CustomerSubscription.builder().id(UUID.randomUUID()).planCode("FREE")
                .status(SubscriptionStatus.TRIALING).build();
        when(customerSubscriptionRepository.findAll()).thenReturn(List.of(sinFin));

        useCase.expireFreeTrials();

        verify(customerSubscriptionRepository, never()).save(any());
    }

    /* ==================== Suscripción vigente y cancelación ==================== */

    @Test
    void laSuscripcionVigenteIgnoraLasCanceladasYSeQuedaConLaMasReciente() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        CustomerSubscription vieja = CustomerSubscription.builder().id(UUID.randomUUID())
                .status(SubscriptionStatus.ACTIVE).createdAt(t0).build();
        CustomerSubscription nueva = CustomerSubscription.builder().id(UUID.randomUUID())
                .status(SubscriptionStatus.PAST_DUE).createdAt(t0.plusSeconds(60)).build();
        CustomerSubscription cancelada = CustomerSubscription.builder().id(UUID.randomUUID())
                .status(SubscriptionStatus.CANCELED).createdAt(t0.plusSeconds(120)).build();
        when(customerSubscriptionRepository.findByUserId(userId)).thenReturn(List.of(vieja, nueva, cancelada));

        assertThat(useCase.currentSubscription(userId).getId()).isEqualTo(nueva.getId());
    }

    @Test
    void sinSuscripcionesLaVigenteEsNula() {
        when(customerSubscriptionRepository.findByUserId(userId)).thenReturn(List.of());

        assertThat(useCase.currentSubscription(userId)).isNull();
    }

    @Test
    void cancelarSinSuscripcionActivaLanzaNotFound() {
        when(customerSubscriptionRepository.findByUserId(userId)).thenReturn(List.of());

        assertThatThrownBy(() -> useCase.cancelMySubscription(userId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void cancelarUnaSuscripcionDeStripeLaProgramaAlFinalDelPeriodo() throws Exception {
        CustomerSubscription sub = CustomerSubscription.builder().id(UUID.randomUUID())
                .status(SubscriptionStatus.ACTIVE).stripeSubscriptionId("sub_1").build();
        when(customerSubscriptionRepository.findByUserId(userId)).thenReturn(List.of(sub));
        when(stripeService.cancelSubscription("sub_1", true))
                .thenReturn(new StripeService.SubResult("sub_1", "active", 1000L, 2000L));

        useCase.cancelMySubscription(userId);

        ArgumentCaptor<CustomerSubscription> captor = ArgumentCaptor.forClass(CustomerSubscription.class);
        verify(customerSubscriptionRepository).save(captor.capture());
        // Sigue ACTIVE hasta el final del periodo ya pagado; el corte queda en cancelAt.
        assertThat(captor.getValue().getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(captor.getValue().getCancelAt()).isEqualTo(Instant.ofEpochSecond(2000L));
    }

    @Test
    void cancelarUnaSuscripcionLocalNoLlamaAStripe() throws Exception {
        CustomerSubscription sub = CustomerSubscription.builder().id(UUID.randomUUID())
                .status(SubscriptionStatus.ACTIVE).stripeSubscriptionId("  ").build();
        when(customerSubscriptionRepository.findByUserId(userId)).thenReturn(List.of(sub));

        useCase.cancelMySubscription(userId);

        ArgumentCaptor<CustomerSubscription> captor = ArgumentCaptor.forClass(CustomerSubscription.class);
        verify(customerSubscriptionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(captor.getValue().getCanceledAt()).isNotNull();
        verify(stripeService, never()).cancelSubscription(anyString(), eq(true));
    }

    /* ==================== Sincronización desde Stripe ==================== */

    @Test
    void elWebhookSinIdDeSuscripcionNoTocaLaBaseDeDatos() {
        useCase.syncFromStripe("  ", "active", 1L, null);
        useCase.syncFromStripe(null, "active", 1L, null);

        verifyNoInteractions(customerSubscriptionRepository);
    }

    @Test
    void unEstadoDesconocidoDeStripeSeGuardaComoIncomplete() {
        CustomerSubscription sub = CustomerSubscription.builder().id(UUID.randomUUID()).build();
        when(customerSubscriptionRepository.findByStripeSubscriptionId("sub_1")).thenReturn(Optional.of(sub));

        useCase.syncFromStripe("sub_1", "unpaid", null, null);

        ArgumentCaptor<CustomerSubscription> captor = ArgumentCaptor.forClass(CustomerSubscription.class);
        verify(customerSubscriptionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SubscriptionStatus.INCOMPLETE);
    }

    @Test
    void unEstadoNuloDeStripeTambienSeGuardaComoIncomplete() {
        CustomerSubscription sub = CustomerSubscription.builder().id(UUID.randomUUID()).build();
        when(customerSubscriptionRepository.findByStripeSubscriptionId("sub_1")).thenReturn(Optional.of(sub));

        useCase.syncFromStripe("sub_1", null, null, null);

        ArgumentCaptor<CustomerSubscription> captor = ArgumentCaptor.forClass(CustomerSubscription.class);
        verify(customerSubscriptionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SubscriptionStatus.INCOMPLETE);
    }

    @Test
    void elWebhookDeCancelacionSellaLaFechaDeBajaYElFinDePeriodo() {
        CustomerSubscription sub = CustomerSubscription.builder().id(UUID.randomUUID()).build();
        when(customerSubscriptionRepository.findByStripeSubscriptionId("sub_1")).thenReturn(Optional.of(sub));

        useCase.syncFromStripe("sub_1", "canceled", 3000L, 4000L);

        ArgumentCaptor<CustomerSubscription> captor = ArgumentCaptor.forClass(CustomerSubscription.class);
        verify(customerSubscriptionRepository).save(captor.capture());
        CustomerSubscription saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(saved.getCurrentPeriodEnd()).isEqualTo(Instant.ofEpochSecond(3000L));
        assertThat(saved.getCancelAt()).isEqualTo(Instant.ofEpochSecond(4000L));
        assertThat(saved.getCanceledAt()).isNotNull();
    }

    @ParameterizedTest
    @CsvSource({"active,ACTIVE", "trialing,TRIALING", "past_due,PAST_DUE", "paused,PAUSED", "canceled,CANCELED"})
    void losEstadosConocidosDeStripeSeTraducenAlEnumDelDominio(String stripeStatus, SubscriptionStatus expected) {
        CustomerSubscription sub = CustomerSubscription.builder().id(UUID.randomUUID()).build();
        when(customerSubscriptionRepository.findByStripeSubscriptionId("sub_1")).thenReturn(Optional.of(sub));

        useCase.syncFromStripe("sub_1", stripeStatus, null, null);

        ArgumentCaptor<CustomerSubscription> captor = ArgumentCaptor.forClass(CustomerSubscription.class);
        verify(customerSubscriptionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(expected);
    }

    /* ==================== Tarjetas guardadas ==================== */

    @Test
    void sinStripeActivoLasOperacionesDeTarjetaSeRechazan() {
        when(stripeService.isEnabled()).thenReturn(false);

        assertThatThrownBy(() -> useCase.createSetupIntentSecret(userId)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> useCase.listCards(userId)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> useCase.setDefaultCard(userId, "pm_1")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> useCase.deleteCard(userId, "pm_1")).isInstanceOf(BusinessException.class);
    }

    @Test
    void unUsuarioSinCustomerDeStripeNoTieneTarjetas() throws Exception {
        when(stripeService.isEnabled()).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(false)));

        assertThat(useCase.listCards(userId)).isEmpty();
        // No se debe crear un Customer solo por consultar el listado de tarjetas.
        verify(stripeService, never()).listCards(anyString());
    }

    @Test
    void elListadoDeTarjetasMarcaCualEsLaPredeterminada() throws Exception {
        UserEntity user = user(false);
        user.setStripeCustomerId("cus_1");
        when(stripeService.isEnabled()).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(stripeService.defaultPaymentMethodId("cus_1")).thenReturn("pm_2");
        // Los dobles se construyen ANTES del when(): crearlos dentro dejaría el stubbing a medias.
        List<PaymentMethod> cards = List.of(card("pm_1", "visa", "4242"), paymentMethodWithoutCard("pm_2"));
        when(stripeService.listCards("cus_1")).thenReturn(cards);

        List<CustomerSubscriptionUseCase.CardInfo> result = useCase.listCards(userId);

        assertThat(result).extracting(CustomerSubscriptionUseCase.CardInfo::id).containsExactly("pm_1", "pm_2");
        assertThat(result.get(0).isDefault()).isFalse();
        assertThat(result.get(0).brand()).isEqualTo("visa");
        assertThat(result.get(0).last4()).isEqualTo("4242");
        assertThat(result.get(1).isDefault()).isTrue();
        // Un PaymentMethod sin card (p. ej. otro tipo) no debe reventar el listado.
        assertThat(result.get(1).brand()).isNull();
    }

    @Test
    void noSePuedeMarcarComoPredeterminadaUnaTarjetaDeOtroCliente() throws Exception {
        UserEntity user = user(false);
        user.setStripeCustomerId("cus_1");
        when(stripeService.isEnabled()).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        List<PaymentMethod> cards = List.of(card("pm_1", "visa", "4242"));
        when(stripeService.listCards("cus_1")).thenReturn(cards);

        assertThatThrownBy(() -> useCase.setDefaultCard(userId, "pm_ajena")).isInstanceOf(NotFoundException.class);
        verify(stripeService, never()).setDefaultPaymentMethod(anyString(), anyString());
    }

    @Test
    void noSePuedeBorrarUnaTarjetaDeOtroCliente() throws Exception {
        UserEntity user = user(false);
        user.setStripeCustomerId("cus_1");
        when(stripeService.isEnabled()).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        List<PaymentMethod> cards = List.of(card("pm_1", "visa", "4242"));
        when(stripeService.listCards("cus_1")).thenReturn(cards);

        assertThatThrownBy(() -> useCase.deleteCard(userId, "pm_ajena")).isInstanceOf(NotFoundException.class);
        verify(stripeService, never()).detachPaymentMethod(anyString());
    }

    @Test
    void borrarLaPropiaTarjetaLaDesvinculaEnStripe() throws Exception {
        UserEntity user = user(false);
        user.setStripeCustomerId("cus_1");
        when(stripeService.isEnabled()).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        List<PaymentMethod> cards = List.of(card("pm_1", "visa", "4242"));
        when(stripeService.listCards("cus_1")).thenReturn(cards);

        useCase.deleteCard(userId, "pm_1");

        verify(stripeService).detachPaymentMethod("pm_1");
    }

    @Test
    void elCustomerDeStripeSeCreaYSePersisteLaPrimeraVezQueHaceFalta() throws Exception {
        UserEntity user = user(false);
        user.setEmail("cliente@example.com");
        Customer customer = mock(Customer.class);
        when(customer.getId()).thenReturn("cus_nuevo");
        SetupIntent intent = mock(SetupIntent.class);
        when(intent.getClientSecret()).thenReturn("seti_secret");
        when(stripeService.isEnabled()).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(stripeService.getOrCreateCustomer(null, "cliente@example.com", userId.toString())).thenReturn(customer);
        when(stripeService.createSetupIntent("cus_nuevo")).thenReturn(intent);

        assertThat(useCase.createSetupIntentSecret(userId)).isEqualTo("seti_secret");
        // El espejo local del customer se guarda para no crear un Customer duplicado en el siguiente intento.
        assertThat(user.getStripeCustomerId()).isEqualTo("cus_nuevo");
        verify(userRepository).save(user);
    }

    @Test
    void elUsuarioInexistenteNoTieneCustomerDeStripe() {
        when(stripeService.isEnabled()).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.createSetupIntentSecret(userId)).isInstanceOf(NotFoundException.class);
    }

    /* ==================== Facturas ==================== */

    @Test
    void sinStripeNoHayHistorialDeFacturas() throws Exception {
        when(stripeService.isEnabled()).thenReturn(false);

        assertThat(useCase.listInvoices(userId)).isEmpty();
        verifyNoInteractions(userRepository);
    }

    @Test
    void unUsuarioSinCustomerNoTieneFacturas() throws Exception {
        when(stripeService.isEnabled()).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(false)));

        assertThat(useCase.listInvoices(userId)).isEmpty();
    }

    @Test
    void elPdfDeUnaFacturaQueNoEsDelUsuarioNoSeGenera() throws Exception {
        UserEntity user = user(false);
        user.setStripeCustomerId("cus_1");
        when(stripeService.isEnabled()).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(stripeService.listInvoices("cus_1", 50)).thenReturn(List.of(invoice("F-001")));

        assertThatThrownBy(() -> useCase.renderInvoicePdf(userId, "F-AJENA", "es"))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(invoiceService);
    }

    @Test
    void sinCustomerNoSePuedeDescargarNingunPdf() {
        when(stripeService.isEnabled()).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(false)));

        assertThatThrownBy(() -> useCase.renderInvoicePdf(userId, "F-001", "es")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void elPdfDeLaPropiaFacturaSeRenderizaConSusImportes() throws Exception {
        UserEntity user = user(false);
        user.setStripeCustomerId("cus_1");
        when(stripeService.isEnabled()).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(stripeService.listInvoices("cus_1", 50)).thenReturn(List.of(invoice("F-001")));
        when(invoiceService.renderPlanInvoicePdf(any(), eq("es"))).thenReturn(new byte[]{1, 2, 3});

        byte[] pdf = useCase.renderInvoicePdf(userId, "F-001", "es");

        assertThat(pdf).hasSize(3);
        ArgumentCaptor<InvoiceService.PlanInvoiceData> captor = ArgumentCaptor
                .forClass(InvoiceService.PlanInvoiceData.class);
        verify(invoiceService).renderPlanInvoicePdf(captor.capture(), eq("es"));
        assertThat(captor.getValue().number()).isEqualTo("F-001");
        assertThat(captor.getValue().totalCents()).isEqualTo(1210L);
        assertThat(captor.getValue().paid()).isTrue();
    }

    /* ==================== Contratación con tarjeta guardada ==================== */

    @Test
    void elPlanGratisSeContrataSinPasarPorStripe() throws Exception {
        UUID subId = UUID.randomUUID();
        when(stripeService.isEnabled()).thenReturn(true);
        when(planRepository.findByCode("FREE")).thenReturn(Optional.of(plan("FREE", 0, 0)));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(false)));
        when(customerSubscriptionRepository.save(any()))
                .thenAnswer(inv -> ((CustomerSubscription) inv.getArgument(0)).withId(subId));

        CustomerSubscriptionUseCase.SubscribeOutcome out = useCase.subscribeWithSavedCard(userId, "FREE", "MONTHLY");

        assertThat(out.subscriptionId()).isEqualTo(subId.toString());
        assertThat(out.status()).isEqualTo("active");
        verify(stripeService, never()).createSubscription(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void contratarUnPlanDePagoExigePaisEnElPerfil() {
        when(stripeService.isEnabled()).thenReturn(true);
        when(planRepository.findByCode("PRO")).thenReturn(Optional.of(plan("PRO", 5000, 50000)));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(false)));

        // Sin país no se puede calcular el IVA de la factura, así que no se cobra.
        assertThatThrownBy(() -> useCase.subscribeWithSavedCard(userId, "PRO", "MONTHLY"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("país");
    }

    @Test
    void contratarUnPlanDePagoExigeTarjetaGuardada() throws Exception {
        UserEntity user = user(false);
        user.setCountry("ES");
        user.setStripeCustomerId("cus_1");
        when(stripeService.isEnabled()).thenReturn(true);
        when(planRepository.findByCode("PRO")).thenReturn(Optional.of(plan("PRO", 5000, 50000)));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(stripeService.defaultOrFirstCardId("cus_1")).thenReturn(null);

        assertThatThrownBy(() -> useCase.subscribeWithSavedCard(userId, "PRO", "MONTHLY"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("tarjeta");
    }

    @Test
    void enEurosElPlanSeCobraEnEurosYLaFilaLocalNaceIncompleta() throws Exception {
        CurrencyHolder.set("EUR");
        UUID localId = UUID.randomUUID();
        prepararContratacionDePago();
        when(currencyService.toUsd(new BigDecimal("50.00"), "CNY")).thenReturn(new BigDecimal("7"));
        when(currencyService.usdTo(new BigDecimal("7"), "EUR")).thenReturn(new BigDecimal("6.40"));
        when(customerSubscriptionRepository.save(any()))
                .thenAnswer(inv -> withIdIfMissing(inv.getArgument(0), localId));

        CustomerSubscriptionUseCase.SubscribeOutcome out = useCase.subscribeWithSavedCard(userId, "PRO", "MONTHLY");

        assertThat(out.subscriptionId()).isEqualTo("sub_9");
        assertThat(out.status()).isEqualTo("active");
        // 7 USD → 6,40 EUR → 6 EUR → 600 céntimos, y el id de la fila local viaja como metadata a Stripe.
        verify(stripeService).ensureRecurringPrice(eq("PRO"), eq("MONTHLY"), eq(600L), eq("eur"), anyString());
        verify(stripeService).createSubscription("cus_1", "price_1", "pm_1", "txr_1", "PRO", userId.toString(),
                localId.toString());
        ArgumentCaptor<CustomerSubscription> captor = ArgumentCaptor.forClass(CustomerSubscription.class);
        verify(customerSubscriptionRepository, times(2)).save(captor.capture());
        // Primero se persiste INCOMPLETE (su id viaja como metadata a Stripe) y luego el resultado real.
        assertThat(captor.getAllValues().get(0).getStatus()).isEqualTo(SubscriptionStatus.INCOMPLETE);
        assertThat(captor.getAllValues().get(1).getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(captor.getAllValues().get(1).getStripeSubscriptionId()).isEqualTo("sub_9");
        assertThat(captor.getAllValues().get(1).getCurrentPeriodStart()).isEqualTo(Instant.ofEpochSecond(100L));
        assertThat(captor.getAllValues().get(1).getCurrentPeriodEnd()).isEqualTo(Instant.ofEpochSecond(200L));
    }

    @Test
    void enUsdElPlanSeCobraEnUsdRedondeadoAEntero() throws Exception {
        CurrencyHolder.set("USD");
        prepararContratacionDePago();
        when(currencyService.toUsd(new BigDecimal("50.00"), "CNY")).thenReturn(new BigDecimal("7.60"));
        when(customerSubscriptionRepository.save(any()))
                .thenAnswer(inv -> withIdIfMissing(inv.getArgument(0), UUID.randomUUID()));

        useCase.subscribeWithSavedCard(userId, "PRO", "MONTHLY");

        // 7,60 USD → 8 USD → 800 centavos, en la propia divisa USD (sin conversión).
        verify(stripeService).ensureRecurringPrice(eq("PRO"), eq("MONTHLY"), eq(800L), eq("usd"), anyString());
    }

    @Test
    void conOtraDivisaSeMuestraEnEllaPeroSeCobraSuEquivalenteEnUsd() throws Exception {
        CurrencyHolder.set("MXN");
        prepararContratacionDePago();
        when(currencyService.toUsd(new BigDecimal("50.00"), "CNY")).thenReturn(new BigDecimal("7"));
        when(currencyService.usdTo(new BigDecimal("7"), "MXN")).thenReturn(new BigDecimal("128.40"));
        when(currencyService.toUsd(new BigDecimal("128"), "MXN")).thenReturn(new BigDecimal("6.98"));
        when(customerSubscriptionRepository.save(any()))
                .thenAnswer(inv -> withIdIfMissing(inv.getArgument(0), UUID.randomUUID()));

        useCase.subscribeWithSavedCard(userId, "PRO", "MONTHLY");

        // Se muestra 128 MXN (entero) y se cobra su equivalente: 6,98 USD → 698 centavos.
        verify(stripeService).ensureRecurringPrice(eq("PRO"), eq("MONTHLY"), eq(698L), eq("usd"), anyString());
    }

    @Test
    void unImporteQueRedondeaACeroSeCobraAlMinimoDeUnCentimo() throws Exception {
        CurrencyHolder.set("USD");
        prepararContratacionDePago();
        when(currencyService.toUsd(new BigDecimal("50.00"), "CNY")).thenReturn(new BigDecimal("0.004"));
        when(customerSubscriptionRepository.save(any()))
                .thenAnswer(inv -> withIdIfMissing(inv.getArgument(0), UUID.randomUUID()));

        useCase.subscribeWithSavedCard(userId, "PRO", "MONTHLY");

        // Stripe rechaza importes de 0: el cobro se eleva al mínimo en vez de fallar.
        verify(stripeService).ensureRecurringPrice(eq("PRO"), eq("MONTHLY"), eq(1L), eq("usd"), anyString());
    }

    @Test
    void elPlanAnualCobraElPrecioAnualDelPlan() throws Exception {
        CurrencyHolder.set("USD");
        prepararContratacionDePago();
        when(currencyService.toUsd(new BigDecimal("500.00"), "CNY")).thenReturn(new BigDecimal("70"));
        when(customerSubscriptionRepository.save(any()))
                .thenAnswer(inv -> withIdIfMissing(inv.getArgument(0), UUID.randomUUID()));

        useCase.subscribeWithSavedCard(userId, "PRO", "yearly");

        verify(stripeService).ensureRecurringPrice(eq("PRO"), eq("YEARLY"), eq(7000L), eq("usd"), anyString());
    }

    /* ==================== helpers ==================== */

    /** Escenario común: Stripe activo, plan PRO de pago, usuario con país, customer y tarjeta. */
    private void prepararContratacionDePago() throws Exception {
        UserEntity user = user(false);
        user.setCountry("ES");
        user.setStripeCustomerId("cus_1");
        when(stripeService.isEnabled()).thenReturn(true);
        when(planRepository.findByCode("PRO")).thenReturn(Optional.of(plan("PRO", 5000, 50000)));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(stripeService.defaultOrFirstCardId("cus_1")).thenReturn("pm_1");
        when(countryTaxService.rateBpsFor("ES")).thenReturn(2100);
        when(stripeService.ensureTaxRate("ES", 2100)).thenReturn("txr_1");
        when(stripeService.ensureRecurringPrice(anyString(), anyString(), anyLong(), anyString(), anyString()))
                .thenReturn("price_1");
        when(stripeService.createSubscription(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString())).thenReturn(new StripeService.SubResult("sub_9", "active", 100L, 200L));
    }

    private static CustomerSubscription withIdIfMissing(CustomerSubscription model, UUID id) {
        return model.getId() == null ? model.withId(id) : model;
    }

    private static SubscriptionPlanEntity plan(String code, int monthlyCents, int yearlyCents) {
        SubscriptionPlanEntity plan = SubscriptionPlanEntity.builder().code(code).name(code)
                .priceMonthlyCents(monthlyCents).priceYearlyCents(yearlyCents).currency("CNY").build();
        plan.setId(UUID.randomUUID());
        return plan;
    }

    private static UserEntity user(boolean freeTrialUsed) {
        UserEntity user = UserEntity.builder().email("u@example.com").freeTrialUsed(freeTrialUsed).build();
        user.setId(UUID.randomUUID());
        return user;
    }

    private static PaymentMethod card(String id, String brand, String last4) {
        PaymentMethod pm = mock(PaymentMethod.class);
        PaymentMethod.Card c = mock(PaymentMethod.Card.class);
        when(pm.getId()).thenReturn(id);
        when(pm.getCard()).thenReturn(c);
        when(c.getBrand()).thenReturn(brand);
        when(c.getLast4()).thenReturn(last4);
        return pm;
    }

    /** PaymentMethod sin objeto {@code card} (otro tipo de método de pago). */
    private static PaymentMethod paymentMethodWithoutCard(String id) {
        PaymentMethod pm = mock(PaymentMethod.class);
        when(pm.getId()).thenReturn(id);
        when(pm.getCard()).thenReturn(null);
        return pm;
    }

    private static StripeService.InvoiceInfo invoice(String number) {
        return new StripeService.InvoiceInfo("in_1", number, 1210L, "eur", "paid", 1000L, "http://pdf", "http://hosted",
                1000L, 210L, 1L, 2L, "Plan PRO", "Cliente", "u@example.com");
    }
}
