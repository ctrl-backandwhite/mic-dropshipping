package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.mapper.SubscriptionPlanUpdateMapper;
import com.nexaplatform.dropshipping.application.usecase.impl.SubscriptionPlanUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.SubscriptionPlan;
import com.nexaplatform.dropshipping.domain.repository.SubscriptionPlanRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionPlanUseCaseImplTest {

    @Mock
    SubscriptionPlanRepository subscriptionPlanRepository;
    @Mock
    SubscriptionPlanUpdateMapper subscriptionPlanUpdateMapper;
    @InjectMocks
    SubscriptionPlanUseCaseImpl useCase;

    @Test
    void save_delegatesToRepository() {
        SubscriptionPlan model = SubscriptionPlan.builder().code("pro").build();
        when(subscriptionPlanRepository.save(model)).thenReturn(model.withId(UUID.randomUUID()));

        SubscriptionPlan saved = useCase.save(model);

        assertThat(saved.getId()).isNotNull();
        verify(subscriptionPlanRepository).save(model);
    }

    @Test
    void getById_throwsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(subscriptionPlanRepository.getById(id)).thenReturn(null);

        assertThatThrownBy(() -> useCase.getById(id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void update_appliesPartialUpdateAndPersists() {
        UUID id = UUID.randomUUID();
        SubscriptionPlan existing = SubscriptionPlan.builder().id(id).code("pro").name("Pro").build();
        SubscriptionPlan incoming = SubscriptionPlan.builder().code("pro").name("Pro+").build();
        when(subscriptionPlanRepository.getById(id)).thenReturn(existing);
        when(subscriptionPlanRepository.update(existing)).thenReturn(existing);

        useCase.update(incoming, id);

        verify(subscriptionPlanUpdateMapper).updateFromModel(incoming, existing);
        verify(subscriptionPlanRepository).update(existing);
    }
}
