package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.mapper.CustomerSubscriptionUpdateMapper;
import com.nexaplatform.dropshipping.application.usecase.SubscriptionPlanUseCase;
import com.nexaplatform.dropshipping.application.usecase.impl.CustomerSubscriptionUseCaseImpl;
import com.nexaplatform.dropshipping.domain.enums.SubscriptionStatus;
import com.nexaplatform.dropshipping.domain.model.CustomerSubscription;
import com.nexaplatform.dropshipping.domain.model.SubscribeResult;
import com.nexaplatform.dropshipping.domain.model.SubscriptionPlan;
import com.nexaplatform.dropshipping.domain.repository.CustomerSubscriptionRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.stripe.StripeService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SubscriptionPlanEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SubscriptionPlanRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CustomerSubscriptionUseCaseImpl}. Mockito drives the ports:
 * the domain subscription repository, the legacy plan Spring Data repository (used
 * for code/Stripe lookups), the delegated {@link SubscriptionPlanUseCase} and the
 * {@link StripeService}. Replaces the old {@code SubscriptionServiceAdminTest}.
 */
@ExtendWith(MockitoExtension.class)
class CustomerSubscriptionUseCaseImplTest {

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
    com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository userRepository;
    @Mock
    com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService currencyService;
    @Mock
    com.nexaplatform.dropshipping.application.service.CountryTaxService countryTaxService;
    @Mock
    com.nexaplatform.dropshipping.application.service.InvoiceService invoiceService;

    @InjectMocks
    CustomerSubscriptionUseCaseImpl useCase;

    @Captor
    ArgumentCaptor<SubscriptionPlan> mergedCaptor;

    @Test
    void listAdminSubscriptions_filtersByStatus() {
        var active = CustomerSubscription.builder().status(SubscriptionStatus.ACTIVE).build();
        var canceled = CustomerSubscription.builder().status(SubscriptionStatus.CANCELED).build();
        when(customerSubscriptionRepository.findAll()).thenReturn(List.of(active, canceled));

        List<CustomerSubscription> result = useCase.listAdminSubscriptions("active");

        assertThat(result).containsExactly(active);
    }

    @Test
    void listAdminSubscriptions_blankStatusReturnsAll() {
        var active = CustomerSubscription.builder().status(SubscriptionStatus.ACTIVE).build();
        var canceled = CustomerSubscription.builder().status(SubscriptionStatus.CANCELED).build();
        when(customerSubscriptionRepository.findAll()).thenReturn(List.of(active, canceled));

        assertThat(useCase.listAdminSubscriptions(null)).containsExactly(active, canceled);
    }

    @Test
    void updatePlan_resolvesByCodeMergesEditableFieldsAndDelegates() {
        UUID planId = UUID.randomUUID();
        var planEntity = SubscriptionPlanEntity.builder().code("pro").name("Pro").build();
        planEntity.setId(planId);
        when(planRepository.findByCode("pro")).thenReturn(Optional.of(planEntity));
        var current = SubscriptionPlan.builder().id(planId).code("pro").name("Pro").currency("USD").position(1)
                .priceMonthlyCents(1000).priceYearlyCents(10000).build();
        when(subscriptionPlanUseCase.getById(planId)).thenReturn(current);
        var changes = SubscriptionPlan.builder().name("Pro+").priceMonthlyCents(4900).build();
        var updated = SubscriptionPlan.builder().id(planId).name("Pro+").build();
        when(subscriptionPlanUseCase.update(mergedCaptor.capture(), eq(planId))).thenReturn(updated);

        SubscriptionPlan result = useCase.updatePlan("pro", changes);

        assertThat(result).isSameAs(updated);
        SubscriptionPlan merged = mergedCaptor.getValue();
        // Editable fields applied, non-editable columns preserved from the current plan.
        assertThat(merged.getName()).isEqualTo("Pro+");
        assertThat(merged.getPriceMonthlyCents()).isEqualTo(4900);
        assertThat(merged.getPriceYearlyCents()).isEqualTo(10000);
        assertThat(merged.getCode()).isEqualTo("pro");
        assertThat(merged.getCurrency()).isEqualTo("USD");
        assertThat(merged.getPosition()).isEqualTo(1);
    }

    @Test
    void updatePlan_throwsWhenPlanNotFound() {
        when(planRepository.findByCode("ghost")).thenReturn(Optional.empty());
        var changes = SubscriptionPlan.builder().build();

        assertThatThrownBy(() -> useCase.updatePlan("ghost", changes)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void subscribe_devMode_createsSubscriptionAndReturnsLocalUrl() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID subId = UUID.randomUUID();
        var planEntity = SubscriptionPlanEntity.builder().code("pro").build();
        planEntity.setId(planId);
        when(planRepository.findByCode("pro")).thenReturn(Optional.of(planEntity));
        when(userRepository.findById(userId)).thenReturn(Optional.of(
                org.mockito.Mockito.mock(com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity.class)));
        when(stripeService.isEnabled()).thenReturn(false);
        var saved = CustomerSubscription.builder().id(subId).planId(planId).userId(userId)
                .status(SubscriptionStatus.ACTIVE).build();
        when(customerSubscriptionRepository.save(any())).thenReturn(saved);

        SubscribeResult result = useCase.subscribe(userId, "pro", "MONTHLY");

        assertThat(result.getCheckoutUrl()).isEqualTo("/billing/success?dev=1");
        assertThat(result.getSessionId()).isEqualTo(subId.toString());
        verify(customerSubscriptionRepository).save(any(CustomerSubscription.class));
    }

    @Test
    void createSubscription_buildsActiveModelWithResolvedPlanId() {
        UUID userId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        // Plan de PAGO (con precio) → no cae en la prueba gratis; honra el periodo YEARLY solicitado.
        var planEntity = SubscriptionPlanEntity.builder().code("pro").priceMonthlyCents(999).priceYearlyCents(9999).build();
        planEntity.setId(planId);
        when(planRepository.findByCode("pro")).thenReturn(Optional.of(planEntity));
        when(customerSubscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CustomerSubscription result = useCase.createSubscription(userId, "pro", "YEARLY");

        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getPlanId()).isEqualTo(planId);
        assertThat(result.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(result.getBillingPeriod()).isEqualTo("YEARLY");
    }

    @Test
    void listForUser_delegatesToRepository() {
        UUID userId = UUID.randomUUID();
        var sub = CustomerSubscription.builder().id(UUID.randomUUID()).userId(userId).build();
        when(customerSubscriptionRepository.findByUserId(userId)).thenReturn(List.of(sub));

        assertThat(useCase.listForUser(userId)).containsExactly(sub);
    }

    @Test
    void listAdminPlans_sortsByPosition() {
        var p2 = SubscriptionPlan.builder().code("b").position(2).build();
        var p1 = SubscriptionPlan.builder().code("a").position(1).build();
        when(subscriptionPlanUseCase.findAll()).thenReturn(List.of(p2, p1));

        assertThat(useCase.listAdminPlans()).containsExactly(p1, p2);
    }

    @Test
    void listPublicPlans_delegatesToLegacyPlanRepository() {
        var plan = SubscriptionPlanEntity.builder().code("pro").build();
        // listPublicPlans usa findActiveWithFeatures() (JOIN FETCH de features) para evitar el
        // LazyInitializationException al mapear plan.features fuera de la transacción.
        when(planRepository.findActiveWithFeatures()).thenReturn(List.of(plan));

        assertThat(useCase.listPublicPlans()).containsExactly(plan);
    }

    @Test
    void update_appliesPartialUpdateOntoExisting() {
        UUID id = UUID.randomUUID();
        var existing = CustomerSubscription.builder().id(id).status(SubscriptionStatus.ACTIVE).build();
        var changes = CustomerSubscription.builder().status(SubscriptionStatus.PAUSED).build();
        when(customerSubscriptionRepository.getById(id)).thenReturn(existing);
        when(customerSubscriptionRepository.update(existing)).thenReturn(existing);

        useCase.update(changes, id);

        verify(customerSubscriptionUpdateMapper).updateFromModel(changes, existing);
        verify(customerSubscriptionRepository).update(existing);
    }

    @Test
    void getById_throwsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(customerSubscriptionRepository.getById(id)).thenReturn(null);

        assertThatThrownBy(() -> useCase.getById(id)).isInstanceOf(NotFoundException.class);
    }
}
