package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.usecase.impl.AffiliateUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.Affiliate;
import com.nexaplatform.dropshipping.domain.repository.AffiliateRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AffiliateUseCaseImplTest {

    @Mock AffiliateRepository affiliateRepository;
    @Mock UserRepository userRepository;
    @InjectMocks AffiliateUseCaseImpl useCase;

    @Test
    void getOrCreate_returnsExistingWhenPresent() {
        UUID userId = UUID.randomUUID();
        Affiliate existing = Affiliate.builder().id(UUID.randomUUID()).userId(userId).build();
        when(affiliateRepository.findByUserId(userId)).thenReturn(Optional.of(existing));

        Affiliate result = useCase.getOrCreate(userId);

        assertThat(result).isSameAs(existing);
        verify(affiliateRepository, never()).save(any());
    }

    @Test
    void getOrCreate_createsActiveAccountWithGeneratedCode() {
        UUID userId = UUID.randomUUID();
        UserEntity user = UserEntity.builder().displayName("Jane Doe").email("jane@x.com").build();
        when(affiliateRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(affiliateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        useCase.getOrCreate(userId);

        ArgumentCaptor<Affiliate> captor = ArgumentCaptor.forClass(Affiliate.class);
        verify(affiliateRepository).save(captor.capture());
        Affiliate saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getCode()).startsWith("janedoe-");
    }
}
