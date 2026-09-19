package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.application.mapper.CustomerSubscriptionUpdateMapper;
import com.nexaplatform.dropshipping.application.service.CountryTaxService;
import com.nexaplatform.dropshipping.application.service.InvoiceService;
import com.nexaplatform.dropshipping.application.service.SubscriptionNotificationService;
import com.nexaplatform.dropshipping.application.usecase.SubscriptionPlanUseCase;
import com.nexaplatform.dropshipping.application.usecase.impl.CustomerSubscriptionUseCaseImpl;
import com.nexaplatform.dropshipping.domain.enums.SubscriptionStatus;
import com.nexaplatform.dropshipping.domain.model.CustomerSubscription;
import com.nexaplatform.dropshipping.domain.repository.CustomerSubscriptionRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.integration.stripe.StripeService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SubscriptionPlanEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SubscriptionPlanRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ciclo de vida de una suscripción: es ingreso recurrente, así que un fallo aquí se repite cada mes.
 *
 * <p>Lo que se fija: el mes de prueba se puede usar UNA vez por cuenta —si no, cualquiera encadena
 * pruebas gratis indefinidamente—, vence solo, y el barrido que lo vence NO toca los planes de pago,
 * cuyo ciclo gobierna Stripe: cancelar por error uno de pago cortaría el servicio a alguien que está
 * pagando.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriptionLifecycleTest {

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

    private final UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID planId = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private SubscriptionPlanEntity plan(String code, int monthlyCents, int yearlyCents) {
        SubscriptionPlanEntity p = new SubscriptionPlanEntity();
        p.setId(planId);
        p.setCode(code);
        p.setPriceMonthlyCents(monthlyCents);
        p.setPriceYearlyCents(yearlyCents);
        when(planRepository.findByCode(code)).thenReturn(Optional.of(p));
        when(customerSubscriptionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        return p;
    }

    private UserEntity user(boolean freeTrialUsed) {
        UserEntity u = new UserEntity();
        u.setId(userId);
        u.setFreeTrialUsed(freeTrialUsed);
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        return u;
    }

    // ---------------------------------------------------------------- prueba de 15 días

    @Test
    void laPruebaDeQuinceDiasSeConcedeUnaVezYMarcaLaCuentaParaQueNoSeRepita() {
        plan("FREE", 0, 0);
        UserEntity u = user(false);

        CustomerSubscription sub = useCase.createSubscription(userId, "FREE", "MONTHLY");

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(sub.getCurrentPeriodEnd())
                .isAfter(Instant.now().plus(14, ChronoUnit.DAYS))
                .isBefore(Instant.now().plus(16, ChronoUnit.DAYS));
        assertThat(u.isFreeTrialUsed()).isTrue();
        verify(userRepository).save(u);
    }

    @Test
    void unSegundoMesDePruebaSeRechazaConUnMensajeQueDiceQueHacer() {
        // Sin esto, cualquiera encadena pruebas gratis y no paga nunca.
        plan("FREE", 0, 0);
        user(true);

        assertThatThrownBy(() -> useCase.createSubscription(userId, "FREE", "MONTHLY"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Elige un plan de pago");

        verify(customerSubscriptionRepository, never()).save(any());
    }

    @Test
    void unPlanSinPrecioCuentaComoPruebaAunqueNoSeLlameFree() {
        // Lo que decide es el precio, no el nombre: un plan a 0 puesto a mano en el admin no puede
        // convertirse en barra libre de meses gratis.
        plan("BASICO", 0, 0);
        UserEntity u = user(false);

        useCase.createSubscription(userId, "BASICO", "MONTHLY");

        assertThat(u.isFreeTrialUsed()).isTrue();
    }

    // ---------------------------------------------------------------- planes de pago

    @ParameterizedTest
    @CsvSource({"MONTHLY, 29", "YEARLY, 364"})
    void unPlanDePagoNaceActivoConSuPeriodoYSinConsumirLaPrueba(String period, int minDays) {
        plan("PRO", 2900, 29000);
        UserEntity u = user(false);

        CustomerSubscription sub = useCase.createSubscription(userId, "PRO", period);

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(sub.getBillingPeriod()).isEqualTo(period);
        assertThat(sub.getCurrentPeriodEnd()).isAfter(Instant.now().plus(minDays, ChronoUnit.DAYS));
        // Contratar de pago NO gasta el mes de prueba: si luego se da de baja, sigue teniéndolo.
        assertThat(u.isFreeTrialUsed()).isFalse();
    }

    @Test
    void sinPeriodoIndicadoSeFacturaMensualmente() {
        plan("PRO", 2900, 29000);
        user(false);

        assertThat(useCase.createSubscription(userId, "PRO", null).getBillingPeriod()).isEqualTo("MONTHLY");
    }

    // ---------------------------------------------------------------- vencimiento del mes de prueba

    private CustomerSubscription subscription(String planCode, int monthly, SubscriptionStatus status,
            Instant end, String stripeId) {
        return CustomerSubscription.builder().id(UUID.randomUUID()).userId(userId).planId(planId)
                .planCode(planCode).priceMonthly(monthly).priceYearly(0).status(status)
                .currentPeriodEnd(end).stripeSubscriptionId(stripeId).build();
    }

    @Test
    void elMesDePruebaVencidoSeCancelaParaQueElUsuarioTengaQueContratar() {
        CustomerSubscription vencida = subscription("FREE", 0, SubscriptionStatus.ACTIVE,
                Instant.now().minus(1, ChronoUnit.DAYS), null);
        when(customerSubscriptionRepository.findAll()).thenReturn(List.of(vencida));
        when(customerSubscriptionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        useCase.expireFreeTrials();

        verify(customerSubscriptionRepository).save(org.mockito.ArgumentMatchers.argThat(
                s -> s.getStatus() == SubscriptionStatus.CANCELED && s.getCanceledAt() != null));
    }

    @Test
    void unPlanDePagoVencidoNoSeToca() {
        // Su ciclo lo gobierna Stripe. Cancelarlo aquí cortaría el servicio a alguien que está pagando.
        CustomerSubscription dePago = subscription("PRO", 2900, SubscriptionStatus.ACTIVE,
                Instant.now().minus(1, ChronoUnit.DAYS), null);
        when(customerSubscriptionRepository.findAll()).thenReturn(List.of(dePago));

        useCase.expireFreeTrials();

        verify(customerSubscriptionRepository, never()).save(any());
    }

    @Test
    void unPlanGratuitoGobernadoPorStripeTampocoSeToca() {
        // Lleva identificador de Stripe: aunque su precio sea 0, el ciclo no es nuestro.
        CustomerSubscription conStripe = subscription("FREE", 0, SubscriptionStatus.ACTIVE,
                Instant.now().minus(1, ChronoUnit.DAYS), "sub_123");
        when(customerSubscriptionRepository.findAll()).thenReturn(List.of(conStripe));

        useCase.expireFreeTrials();

        verify(customerSubscriptionRepository, never()).save(any());
    }

    @Test
    void unMesDePruebaQueTodaviaCorreNoSeCancela() {
        CustomerSubscription viva = subscription("FREE", 0, SubscriptionStatus.ACTIVE,
                Instant.now().plus(10, ChronoUnit.DAYS), null);
        when(customerSubscriptionRepository.findAll()).thenReturn(List.of(viva));

        useCase.expireFreeTrials();

        verify(customerSubscriptionRepository, never()).save(any());
    }

    @Test
    void unaSuscripcionYaCanceladaNoSeVuelveACancelar() {
        CustomerSubscription cancelada = subscription("FREE", 0, SubscriptionStatus.CANCELED,
                Instant.now().minus(30, ChronoUnit.DAYS), null);
        when(customerSubscriptionRepository.findAll()).thenReturn(List.of(cancelada));

        useCase.expireFreeTrials();

        verify(customerSubscriptionRepository, never()).save(any());
    }

    // ---------------------------------------------------------------- plan activo del usuario

    @Test
    void elPlanActivoIgnoraLasSuscripcionesCanceladas() {
        // Tras vencer la prueba, el usuario tiene que quedarse SIN plan para que contrate uno de pago.
        CustomerSubscription cancelada = CustomerSubscription.builder().id(UUID.randomUUID()).userId(userId)
                .status(SubscriptionStatus.CANCELED).createdAt(Instant.now().minus(2, ChronoUnit.DAYS)).build();
        when(customerSubscriptionRepository.findByUserId(userId)).thenReturn(List.of(cancelada));

        assertThat(useCase.currentSubscription(userId)).isNull();
    }

    @Test
    void conVariasSuscripcionesVivasManadaLaMasReciente() {
        CustomerSubscription antigua = CustomerSubscription.builder().id(UUID.randomUUID()).userId(userId)
                .planCode("FREE").status(SubscriptionStatus.ACTIVE)
                .createdAt(Instant.now().minus(60, ChronoUnit.DAYS)).build();
        CustomerSubscription reciente = CustomerSubscription.builder().id(UUID.randomUUID()).userId(userId)
                .planCode("PRO").status(SubscriptionStatus.ACTIVE)
                .createdAt(Instant.now().minus(1, ChronoUnit.DAYS)).build();
        when(customerSubscriptionRepository.findByUserId(userId)).thenReturn(List.of(antigua, reciente));

        assertThat(useCase.currentSubscription(userId).getPlanCode()).isEqualTo("PRO");
    }

    @Test
    void sinNingunaSuscripcionElUsuarioNoTienePlan() {
        when(customerSubscriptionRepository.findByUserId(userId)).thenReturn(List.of());

        assertThat(useCase.currentSubscription(userId)).isNull();
    }

    // ---------------------------------------------------------------- sincronización con Stripe

    @ParameterizedTest
    @CsvSource({
            "active,    ACTIVE",
            "trialing,  TRIALING",
            "past_due,  PAST_DUE",
            "canceled,  CANCELED",
            "paused,    PAUSED",
            // Estados de Stripe que no tienen equivalente propio caen en INCOMPLETE, que es la opción
            // segura: deja al usuario SIN plan activo en vez de darle acceso a algo que no está pagando.
            "unpaid,       INCOMPLETE",
            "incomplete,   INCOMPLETE",
            "algo_nuevo,   INCOMPLETE"
    })
    void elEstadoDeStripeSeTraduceAlEstadoLocal(String stripeStatus, String expected) {
        CustomerSubscription sub = subscription("PRO", 2900, SubscriptionStatus.ACTIVE, null, "sub_123");
        when(customerSubscriptionRepository.findByStripeSubscriptionId("sub_123")).thenReturn(Optional.of(sub));
        when(customerSubscriptionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        useCase.syncFromStripe("sub_123", stripeStatus, null, null);

        verify(customerSubscriptionRepository).save(org.mockito.ArgumentMatchers.argThat(
                s -> s.getStatus() == SubscriptionStatus.valueOf(expected)));
    }

    @Test
    void unaCancelacionDesdeStripeDejaConstanciaDeCuandoOcurrio() {
        CustomerSubscription sub = subscription("PRO", 2900, SubscriptionStatus.ACTIVE, null, "sub_123");
        when(customerSubscriptionRepository.findByStripeSubscriptionId("sub_123")).thenReturn(Optional.of(sub));
        when(customerSubscriptionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        useCase.syncFromStripe("sub_123", "canceled", null, null);

        verify(customerSubscriptionRepository).save(org.mockito.ArgumentMatchers.argThat(
                s -> s.getCanceledAt() != null));
    }

    @Test
    void elFinDePeriodoDeStripeActualizaElLocal() {
        // Si no se actualizara, el barrido podría dar por vencida una suscripción que Stripe renovó.
        CustomerSubscription sub = subscription("PRO", 2900, SubscriptionStatus.ACTIVE, null, "sub_123");
        when(customerSubscriptionRepository.findByStripeSubscriptionId("sub_123")).thenReturn(Optional.of(sub));
        when(customerSubscriptionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        long renovadoHasta = Instant.now().plus(30, ChronoUnit.DAYS).getEpochSecond();

        useCase.syncFromStripe("sub_123", "active", renovadoHasta, null);

        verify(customerSubscriptionRepository).save(org.mockito.ArgumentMatchers.argThat(
                s -> s.getCurrentPeriodEnd() != null && s.getCurrentPeriodEnd().isAfter(Instant.now())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void unEventoSinIdentificadorDeSuscripcionNoBuscaNada(String id) {
        useCase.syncFromStripe(id, "active", null, null);
        useCase.syncFromStripe(null, "active", null, null);

        verify(customerSubscriptionRepository, never()).save(any());
    }

    @Test
    void unEventoDeUnaSuscripcionDesconocidaNoCreaNadaNiRevienta() {
        when(customerSubscriptionRepository.findByStripeSubscriptionId("sub_desconocida"))
                .thenReturn(Optional.empty());

        useCase.syncFromStripe("sub_desconocida", "active", null, null);

        verify(customerSubscriptionRepository, never()).save(any());
    }
}
