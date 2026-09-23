package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.AffiliateDtos.PayoutProfileUpdateRequest;
import com.nexaplatform.dropshipping.api.dto.AffiliateDtos.PayoutProfileView;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.application.notifications.NotificationsPublisher;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.infrastructure.integration.search.AffiliateIndexer;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.AffiliateAttributionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.AffiliateCommissionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.AffiliateConversionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.AffiliateJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.AffiliatePayoutRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.AffiliateProgramConfigRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.AffiliateReferralCodeRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.NotificationJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 5: perfil de cobro del afiliado — lectura (IBAN enmascarado) y actualización con
 * verificación de contraseña + validación de IBAN/email.
 */
@ExtendWith(MockitoExtension.class)
class AffiliatePayoutProfileTest {

    @Mock
    AffiliateJpaRepositoryAdapter affiliateRepo;
    @Mock
    AffiliateReferralCodeRepository codeRepo;
    @Mock
    AffiliateAttributionRepository attrRepo;
    @Mock
    AffiliateConversionRepository conversionRepo;
    @Mock
    AffiliateCommissionRepository commissionRepo;
    @Mock
    AffiliateProgramConfigRepository configRepo;
    @Mock
    AffiliatePayoutRepository payoutRepo;
    @Mock
    UserRepository userRepository;
    @Mock
    PasswordEncoder passwordEncoder;
    @Mock
    NotificationJpaRepositoryAdapter notificationRepo;
    @Mock
    NotificationsPublisher notificationsPublisher;
    @Mock
    WalletUseCase walletUseCase;
    @Mock
    AffiliateIndexer affiliateIndexer;

    @InjectMocks
    AffiliateProgramService service;

    private final UUID userId = UUID.randomUUID();
    private static final String STORED_HASH = "hashed-password";
    private static final String VALID_IBAN = "ES9121000418450200051332";

    private UserEntity user() {
        UserEntity u = new UserEntity();
        u.setId(userId);
        u.setPasswordHash(STORED_HASH);
        return u;
    }

    private AffiliateEntity affiliate() {
        AffiliateEntity a = AffiliateEntity.builder().user(user()).code("ref-1").active(true).status("ACTIVE").build();
        a.setId(UUID.randomUUID());
        return a;
    }

    @Test
    void updatePayoutProfile_wrongPassword_throwsBusinessException() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user()));
        when(passwordEncoder.matches("bad-pass", STORED_HASH)).thenReturn(false);
        PayoutProfileUpdateRequest req = new PayoutProfileUpdateRequest("Jane Doe", VALID_IBAN, "CAIXESBBXXX", null,
                "BANK", "bad-pass");

        assertThatThrownBy(() -> service.updatePayoutProfile(userId, req)).isInstanceOf(BusinessException.class);

        verify(affiliateRepo, never()).save(any());
    }

    @Test
    void updatePayoutProfile_invalidIban_throwsBusinessException() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user()));
        when(passwordEncoder.matches("good-pass", STORED_HASH)).thenReturn(true);
        when(affiliateRepo.findByUser_Id(userId)).thenReturn(Optional.of(affiliate()));
        PayoutProfileUpdateRequest req = new PayoutProfileUpdateRequest("Jane Doe", "ES0000000000000000000000",
                "CAIXESBBXXX", null, "BANK", "good-pass");

        assertThatThrownBy(() -> service.updatePayoutProfile(userId, req)).isInstanceOf(BusinessException.class);

        verify(affiliateRepo, never()).save(any());
    }

    @Test
    void updatePayoutProfile_ok_persistsProfile() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user()));
        when(passwordEncoder.matches("good-pass", STORED_HASH)).thenReturn(true);
        when(affiliateRepo.findByUser_Id(userId)).thenReturn(Optional.of(affiliate()));
        when(affiliateRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        PayoutProfileUpdateRequest req = new PayoutProfileUpdateRequest("Jane Doe", VALID_IBAN, "CAIXESBBXXX", null,
                "BANK", "good-pass");

        service.updatePayoutProfile(userId, req);

        ArgumentCaptor<AffiliateEntity> cap = ArgumentCaptor.forClass(AffiliateEntity.class);
        verify(affiliateRepo).save(cap.capture());
        assertThat(cap.getValue().getBankHolder()).isEqualTo("Jane Doe");
        assertThat(cap.getValue().getBankIban()).isEqualTo(VALID_IBAN);
        assertThat(cap.getValue().getBankBic()).isEqualTo("CAIXESBBXXX");
        assertThat(cap.getValue().getPayoutMethod()).isEqualTo("BANK");
    }

    @Test
    void getPayoutProfile_masksIbanAndReflectsCompleteness() {
        AffiliateEntity a = affiliate();
        a.setBankHolder("Jane Doe");
        a.setBankIban(VALID_IBAN);
        a.setBankBic("CAIXESBBXXX");
        when(affiliateRepo.findByUser_Id(userId)).thenReturn(Optional.of(a));

        PayoutProfileView view = service.getPayoutProfile(userId);

        assertThat(view.bankIbanMasked()).isEqualTo("****1332");
        assertThat(view.hasBank()).isTrue();
        assertThat(view.hasPaypal()).isFalse();
    }
}
