package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.in.AdminPlanUpdateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminSubscriptionDtoOut;
import com.nexaplatform.dropshipping.api.dto.OperationResponseDtoOut;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.AdminBillingMapper;
import com.nexaplatform.dropshipping.api.mapper.BillingDtoMapper;
import com.nexaplatform.dropshipping.application.service.SubscriptionService;
import com.nexaplatform.dropshipping.infrastructure.integration.stripe.StripeService;
import com.nexaplatform.dropshipping.domain.enums.SubscriptionStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerSubscriptionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SubscriptionPlanEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CustomerSubscriptionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SubscriptionPlanRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceAdminTest {

    @Mock SubscriptionPlanRepository planRepository;
    @Mock CustomerSubscriptionRepository subscriptionRepository;
    @Mock UserRepository userRepository;
    @Mock AdminBillingMapper adminBillingMapper;
    @Mock BillingDtoMapper billingDtoMapper;
    @Mock StripeService stripeService;

    @Captor ArgumentCaptor<List<CustomerSubscriptionEntity>> subsCaptor;

    private SubscriptionService service() {
        return new SubscriptionService(planRepository, subscriptionRepository, userRepository,
                adminBillingMapper, billingDtoMapper, stripeService);
    }

    @Test
    void listAdminSubscriptions_filtersByStatusAndDelegatesToMapper() {
        var active = CustomerSubscriptionEntity.builder().status(SubscriptionStatus.ACTIVE).build();
        var canceled = CustomerSubscriptionEntity.builder().status(SubscriptionStatus.CANCELED).build();
        when(subscriptionRepository.findAll()).thenReturn(List.of(active, canceled));
        when(adminBillingMapper.toSubscriptionDtos(any())).thenReturn(List.of());

        service().listAdminSubscriptions("active");

        verify(adminBillingMapper).toSubscriptionDtos(subsCaptor.capture());
        assertThat(subsCaptor.getValue()).containsExactly(active);
    }

    @Test
    void updatePlan_appliesPartialUpdateAndSaves() {
        var plan = SubscriptionPlanEntity.builder().code("pro").name("Pro").build();
        when(planRepository.findByCode("pro")).thenReturn(Optional.of(plan));
        var dto = new AdminPlanUpdateDtoIn("Pro+", null, 4900, null, true);

        OperationResponseDtoOut result = service().updatePlan("pro", dto);

        assertThat(result.getCode()).isEqualTo("OK");
        verify(adminBillingMapper).updatePlanFromDto(dto, plan);
        verify(planRepository).save(plan);
    }

    @Test
    void updatePlan_throwsWhenPlanNotFound() {
        when(planRepository.findByCode("ghost")).thenReturn(Optional.empty());
        var dto = new AdminPlanUpdateDtoIn(null, null, null, null, null);
        SubscriptionService svc = service();

        assertThatThrownBy(() -> svc.updatePlan("ghost", dto))
                .isInstanceOf(NotFoundException.class);
    }
}
