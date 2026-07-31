package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.application.notifications.NotificationsPublisher;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.infrastructure.integration.search.AffiliateIndexer;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateCommissionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliatePayoutEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateProgramConfigEntity;
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

import java.math.BigDecimal;
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
 * Task 6: requestPayout(userId, method) — validación de método/datos de cobro y snapshot del
 * destino en el AffiliatePayoutEntity guardado.
 */
@ExtendWith(MockitoExtension.class)
class AffiliateRequestPayoutTest {

    @Mock AffiliateJpaRepositoryAdapter affiliateRepo;
    @Mock AffiliateReferralCodeRepository codeRepo;
    @Mock AffiliateAttributionRepository attrRepo;
    @Mock AffiliateConversionRepository conversionRepo;
    @Mock AffiliateCommissionRepository commissionRepo;
    @Mock AffiliateProgramConfigRepository configRepo;
    @Mock AffiliatePayoutRepository payoutRepo;
    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock NotificationJpaRepositoryAdapter notificationRepo;
    @Mock NotificationsPublisher notificationsPublisher;
    @Mock WalletUseCase walletUseCase;
    @Mock AffiliateIndexer affiliateIndexer;

    @InjectMocks AffiliateProgramService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID affiliateId = UUID.randomUUID();
    private static final String VALID_IBAN = "ES9121000418450200051332";

    /** Stubs the program config; only needed by tests that reach the min-payout check. */
    private void stubConfig() {
        AffiliateProgramConfigEntity config = AffiliateProgramConfigEntity.builder()
                .defaultPercent(new BigDecimal("10.000")).attributionWindowDays(30).returnPeriodDays(14)
                .minPayoutCents(5000).currency("EUR").attributionModel("LAST_CLICK").build();
        when(configRepo.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(config));
    }

    private UserEntity user() {
        UserEntity u = new UserEntity();
        u.setId(userId);
        return u;
    }

    private AffiliateEntity affiliate() {
        AffiliateEntity a = AffiliateEntity.builder().user(user()).code("ref-1").active(true).status("ACTIVE")
                .build();
        a.setId(affiliateId);
        return a;
    }

    private List<AffiliateCommissionEntity> approvedCommissions(long amountCents) {
        AffiliateCommissionEntity c = AffiliateCommissionEntity.builder().affiliateId(affiliateId)
                .conversionId(UUID.randomUUID()).amountCents(amountCents).currency("EUR")
                .percentage(BigDecimal.TEN).status("APPROVED").build();
        return List.of(c);
    }

    @Test
    void requestPayout_bank_withoutIban_throwsPayoutDetailsMissing() {
        when(affiliateRepo.findByUser_Id(userId)).thenReturn(Optional.of(affiliate()));

        assertThatThrownBy(() -> service.requestPayout(userId, "BANK"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("PAYOUT_DETAILS_MISSING");

        verify(payoutRepo, never()).save(any());
    }

    @Test
    void requestPayout_paypal_withoutEmail_throwsPayoutDetailsMissing() {
        when(affiliateRepo.findByUser_Id(userId)).thenReturn(Optional.of(affiliate()));

        assertThatThrownBy(() -> service.requestPayout(userId, "PAYPAL"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("PAYOUT_DETAILS_MISSING");

        verify(payoutRepo, never()).save(any());
    }

    @Test
    void requestPayout_invalidMethod_throwsInvalidPayoutMethod() {
        assertThatThrownBy(() -> service.requestPayout(userId, "CRYPTO"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("INVALID_PAYOUT_METHOD");

        verify(payoutRepo, never()).save(any());
    }

    @Test
    void requestPayout_belowMinimum_throwsBusinessException() {
        stubConfig();
        when(affiliateRepo.findByUser_Id(userId)).thenReturn(Optional.of(affiliate()));
        when(payoutRepo.existsByAffiliateIdAndStatus(affiliateId, "REQUESTED")).thenReturn(false);
        when(commissionRepo.findByAffiliateIdAndStatus(affiliateId, "APPROVED"))
                .thenReturn(approvedCommissions(1000));

        assertThatThrownBy(() -> service.requestPayout(userId, "WALLET"))
                .isInstanceOf(BusinessException.class);

        verify(payoutRepo, never()).save(any());
    }

    @Test
    void requestPayout_bankOk_savesPayoutWithMethodAndIbanSnapshot() {
        stubConfig();
        AffiliateEntity a = affiliate();
        a.setBankHolder("Jane Doe");
        a.setBankIban(VALID_IBAN);
        a.setBankBic("CAIXESBBXXX");
        when(affiliateRepo.findByUser_Id(userId)).thenReturn(Optional.of(a));
        when(payoutRepo.existsByAffiliateIdAndStatus(affiliateId, "REQUESTED")).thenReturn(false);
        when(commissionRepo.findByAffiliateIdAndStatus(affiliateId, "APPROVED"))
                .thenReturn(approvedCommissions(6000));
        when(payoutRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        AffiliatePayoutEntity result = service.requestPayout(userId, "BANK");

        ArgumentCaptor<AffiliatePayoutEntity> cap = ArgumentCaptor.forClass(AffiliatePayoutEntity.class);
        verify(payoutRepo).save(cap.capture());
        assertThat(cap.getValue().getMethod()).isEqualTo("BANK");
        assertThat(cap.getValue().getDestIban()).isEqualTo(VALID_IBAN);
        assertThat(cap.getValue().getDestHolder()).isEqualTo("Jane Doe");
        assertThat(cap.getValue().getDestBic()).isEqualTo("CAIXESBBXXX");
        assertThat(cap.getValue().getDestPaypalEmail()).isNull();
        assertThat(result.getMethod()).isEqualTo("BANK");
    }

}
