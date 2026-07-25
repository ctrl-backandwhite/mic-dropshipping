package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.application.notifications.NotificationsPublisher;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.domain.model.WalletTransaction;
import com.nexaplatform.dropshipping.infrastructure.integration.search.AffiliateIndexer;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateCommissionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliatePayoutEntity;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 7: approvePayout(payoutId, adminUserId, reference) — pago WALLET (abona a la wallet) vs
 * pago externo BANK/PAYPAL (solo marca PAID con referencia + admin, sin tocar la wallet).
 */
@ExtendWith(MockitoExtension.class)
class AffiliateApprovePayoutTest {

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

    AffiliateProgramService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID affiliateId = UUID.randomUUID();
    private final UUID payoutId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        service = new AffiliateProgramService(affiliateRepo, codeRepo, attrRepo, conversionRepo, commissionRepo,
                configRepo, payoutRepo, userRepository, passwordEncoder, notificationRepo, notificationsPublisher,
                walletUseCase, affiliateIndexer);
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

    private AffiliatePayoutEntity payout(String method, String status) {
        AffiliatePayoutEntity p = AffiliatePayoutEntity.builder().affiliateId(affiliateId).currency("EUR")
                .status(status).method(method).build();
        p.setId(payoutId);
        return p;
    }

    private List<AffiliateCommissionEntity> approvedCommissions(long amountCents) {
        AffiliateCommissionEntity c = AffiliateCommissionEntity.builder().affiliateId(affiliateId)
                .conversionId(UUID.randomUUID()).amountCents(amountCents).currency("EUR")
                .percentage(BigDecimal.TEN).status("APPROVED").build();
        return List.of(c);
    }

    @Test
    void approvePayout_walletMethod_callsAdminTopupAndSetsWalletTxId() {
        AffiliatePayoutEntity p = payout("WALLET", "REQUESTED");
        when(payoutRepo.findById(payoutId)).thenReturn(Optional.of(p));
        when(affiliateRepo.findById(affiliateId)).thenReturn(Optional.of(affiliate()));
        List<AffiliateCommissionEntity> approved = approvedCommissions(6000);
        when(commissionRepo.findByAffiliateIdAndStatus(affiliateId, "APPROVED")).thenReturn(approved);
        WalletTransaction tx = new WalletTransaction();
        UUID txId = UUID.randomUUID();
        tx.setId(txId);
        when(walletUseCase.adminTopup(eq(userId), eq(6000L), any(), any())).thenReturn(tx);
        when(payoutRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        AffiliatePayoutEntity result = service.approvePayout(payoutId); // 1-arg → delegates to (id, null, null)

        verify(walletUseCase).adminTopup(eq(userId), eq(6000L), any(), any());
        assertThat(result.getStatus()).isEqualTo("PAID");
        assertThat(result.getWalletTxId()).isEqualTo(txId);
        assertThat(result.getAmountCents()).isEqualTo(6000);
        assertThat(approved.get(0).getStatus()).isEqualTo("PAID");
        assertThat(approved.get(0).getWalletTxId()).isEqualTo(txId);
        assertThat(approved.get(0).getPayoutId()).isEqualTo(payoutId);
    }

    @Test
    void approvePayout_bankMethodWithReference_marksPaidWithoutTouchingWallet() {
        AffiliatePayoutEntity p = payout("BANK", "REQUESTED");
        when(payoutRepo.findById(payoutId)).thenReturn(Optional.of(p));
        when(affiliateRepo.findById(affiliateId)).thenReturn(Optional.of(affiliate()));
        List<AffiliateCommissionEntity> approved = approvedCommissions(6000);
        when(commissionRepo.findByAffiliateIdAndStatus(affiliateId, "APPROVED")).thenReturn(approved);
        when(payoutRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        AffiliatePayoutEntity result = service.approvePayout(payoutId, adminId, "REF-123");

        verify(walletUseCase, never()).adminTopup(any(), anyLong(), any(), any());
        assertThat(result.getStatus()).isEqualTo("PAID");
        assertThat(result.getPaidReference()).isEqualTo("REF-123");
        assertThat(result.getPaidBy()).isEqualTo(adminId);
        assertThat(result.getWalletTxId()).isNull();
        assertThat(approved.get(0).getStatus()).isEqualTo("PAID");
        assertThat(approved.get(0).getPayoutId()).isEqualTo(payoutId);
    }

    @Test
    void approvePayout_alreadyPaid_isIdempotentAndDoesNotRepay() {
        AffiliatePayoutEntity p = payout("BANK", "PAID");
        when(payoutRepo.findById(payoutId)).thenReturn(Optional.of(p));

        AffiliatePayoutEntity result = service.approvePayout(payoutId, adminId, "REF-999");

        assertThat(result).isSameAs(p);
        assertThat(result.getStatus()).isEqualTo("PAID");
        verify(payoutRepo, never()).save(any());
        verify(walletUseCase, never()).adminTopup(any(), anyLong(), any(), any());
        verify(affiliateRepo, never()).findById(any());
        verify(commissionRepo, never()).findByAffiliateIdAndStatus(any(), any());
    }
}
