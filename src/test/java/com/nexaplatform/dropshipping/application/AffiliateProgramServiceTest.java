package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.AffiliateProgramService;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.domain.model.WalletTransaction;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.application.notifications.NotificationsPublisher;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.*;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AffiliateProgramServiceTest {

    @Mock AffiliateJpaRepositoryAdapter affiliateRepo;
    @Mock AffiliateReferralCodeRepository codeRepo;
    @Mock AffiliateAttributionRepository attrRepo;
    @Mock AffiliateConversionRepository conversionRepo;
    @Mock AffiliateCommissionRepository commissionRepo;
    @Mock AffiliateProgramConfigRepository configRepo;
    @Mock AffiliatePayoutRepository payoutRepo;
    @Mock UserRepository userRepository;
    @Mock NotificationJpaRepositoryAdapter notificationRepo;
    @Mock NotificationsPublisher notificationsPublisher;
    @Mock WalletUseCase walletUseCase;
    @Mock com.nexaplatform.dropshipping.infrastructure.integration.search.AffiliateIndexer affiliateIndexer;

    AffiliateProgramService service;

    private final UUID affiliateId = UUID.randomUUID();
    private final UUID affiliateUserId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        service = new AffiliateProgramService(affiliateRepo, codeRepo, attrRepo, conversionRepo, commissionRepo,
                configRepo, payoutRepo, userRepository, notificationRepo, notificationsPublisher, walletUseCase,
                affiliateIndexer);
        AffiliateProgramConfigEntity config = AffiliateProgramConfigEntity.builder()
                .defaultPercent(new BigDecimal("10.000")).attributionWindowDays(30).returnPeriodDays(14)
                .minPayoutCents(5000).currency("EUR").attributionModel("LAST_CLICK").build();
        when(configRepo.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(config));
        when(conversionRepo.save(any())).thenAnswer(i -> {
            AffiliateConversionEntity c = i.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            return c;
        });
        when(commissionRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(affiliateRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private AffiliateEntity affiliate() {
        UserEntity u = new UserEntity();
        u.setId(affiliateUserId);
        AffiliateEntity a = AffiliateEntity.builder().user(u).code("ref-1").active(true).status("ACTIVE").build();
        a.setId(affiliateId);
        return a;
    }

    private AffiliateAttributionEntity liveAttribution() {
        return AffiliateAttributionEntity.builder().affiliateId(affiliateId).referralCodeId(UUID.randomUUID())
                .referredUserId(customerId).clickedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(86400)).build();
    }

    @Test
    void onOrderPlaced_creates_conversion_and_10pct_commission() {
        when(conversionRepo.existsByOrderId(orderId)).thenReturn(false);
        when(attrRepo.findTopByReferredUserIdAndExpiresAtAfterOrderByClickedAtDesc(eq(customerId), any()))
                .thenReturn(Optional.of(liveAttribution()));
        when(affiliateRepo.findById(affiliateId)).thenReturn(Optional.of(affiliate()));

        service.onOrderPlaced(orderId, customerId, 3200, "EUR"); // subtotal 32.00 EUR

        ArgumentCaptor<AffiliateCommissionEntity> cap = ArgumentCaptor.forClass(AffiliateCommissionEntity.class);
        verify(commissionRepo).save(cap.capture());
        assertThat(cap.getValue().getAmountCents()).isEqualTo(320); // 10% of 3200
        assertThat(cap.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(cap.getValue().getPercentage()).isEqualByComparingTo("10.000");
    }

    @Test
    void onOrderPlaced_is_idempotent_per_order() {
        when(conversionRepo.existsByOrderId(orderId)).thenReturn(true);
        service.onOrderPlaced(orderId, customerId, 3200, "EUR");
        verify(commissionRepo, never()).save(any());
    }

    @Test
    void onOrderPlaced_ignores_self_referral() {
        when(conversionRepo.existsByOrderId(orderId)).thenReturn(false);
        AffiliateAttributionEntity attr = liveAttribution();
        when(attrRepo.findTopByReferredUserIdAndExpiresAtAfterOrderByClickedAtDesc(eq(affiliateUserId), any()))
                .thenReturn(Optional.of(attr));
        when(affiliateRepo.findById(affiliateId)).thenReturn(Optional.of(affiliate()));
        // the customer IS the affiliate's own user → no commission
        service.onOrderPlaced(orderId, affiliateUserId, 3200, "EUR");
        verify(commissionRepo, never()).save(any());
    }

    @Test
    void onOrderPlaced_without_attribution_does_nothing() {
        when(conversionRepo.existsByOrderId(orderId)).thenReturn(false);
        when(attrRepo.findTopByReferredUserIdAndExpiresAtAfterOrderByClickedAtDesc(any(), any()))
                .thenReturn(Optional.empty());
        service.onOrderPlaced(orderId, customerId, 3200, "EUR");
        verify(conversionRepo, never()).save(any());
        verify(commissionRepo, never()).save(any());
    }

    @Test
    void rejectForOrder_rejects_unpaid_commission() {
        AffiliateConversionEntity conv = AffiliateConversionEntity.builder().affiliateId(affiliateId).orderId(orderId)
                .baseAmountCents(3200).currency("EUR").status("CONFIRMED").build();
        conv.setId(UUID.randomUUID());
        AffiliateCommissionEntity comm = AffiliateCommissionEntity.builder().affiliateId(affiliateId)
                .conversionId(conv.getId()).amountCents(320).currency("EUR").percentage(new BigDecimal("10.000"))
                .status("PENDING").build();
        when(conversionRepo.findByOrderId(orderId)).thenReturn(Optional.of(conv));
        when(commissionRepo.findByConversionId(conv.getId())).thenReturn(Optional.of(comm));
        when(affiliateRepo.findById(affiliateId)).thenReturn(Optional.of(affiliate()));

        service.rejectForOrder(orderId);

        assertThat(comm.getStatus()).isEqualTo("REJECTED");
        assertThat(conv.getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void payoutApproved_credits_wallet_above_minimum() {
        AffiliateEntity a = affiliate();
        when(affiliateRepo.findById(affiliateId)).thenReturn(Optional.of(a));
        AffiliateCommissionEntity c1 = AffiliateCommissionEntity.builder().affiliateId(affiliateId)
                .conversionId(UUID.randomUUID()).amountCents(3000).currency("EUR").percentage(BigDecimal.TEN)
                .status("APPROVED").build();
        AffiliateCommissionEntity c2 = AffiliateCommissionEntity.builder().affiliateId(affiliateId)
                .conversionId(UUID.randomUUID()).amountCents(2500).currency("EUR").percentage(BigDecimal.TEN)
                .status("APPROVED").build();
        when(commissionRepo.findByAffiliateIdAndStatus(affiliateId, "APPROVED")).thenReturn(List.of(c1, c2));
        WalletTransaction tx = new WalletTransaction();
        tx.setId(UUID.randomUUID());
        when(walletUseCase.adminTopup(eq(affiliateUserId), eq(5500L), any(), any())).thenReturn(tx);
        // DROP-651: payoutApproved now creates+executes a payout record.
        AffiliatePayoutEntity[] saved = new AffiliatePayoutEntity[1];
        when(payoutRepo.save(any())).thenAnswer(i -> {
            var p = (AffiliatePayoutEntity) i.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            saved[0] = p; return p;
        });
        when(payoutRepo.findById(any())).thenAnswer(i -> Optional.ofNullable(saved[0]));

        long paid = service.payoutApproved(affiliateId, false); // 55.00 EUR ≥ 50.00 min

        assertThat(paid).isEqualTo(5500);
        assertThat(c1.getStatus()).isEqualTo("PAID");
        assertThat(c2.getStatus()).isEqualTo("PAID");
        verify(walletUseCase).adminTopup(eq(affiliateUserId), eq(5500L), any(), any());
    }

    @Test
    void onOrderPlaced_over_period_cap_flags_review() {
        // cap of 1.00 → a 3.20 commission must be flagged for manual review, not auto-pending
        AffiliateProgramConfigEntity capped = AffiliateProgramConfigEntity.builder()
                .defaultPercent(new BigDecimal("10.000")).attributionWindowDays(30).returnPeriodDays(14)
                .minPayoutCents(5000).currency("EUR").attributionModel("LAST_CLICK").maxCommissionPeriodCents(100)
                .maxPeriodDays(30).clickDedupMinutes(30).build();
        when(configRepo.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(capped));
        when(conversionRepo.existsByOrderId(orderId)).thenReturn(false);
        when(attrRepo.findTopByReferredUserIdAndExpiresAtAfterOrderByClickedAtDesc(eq(customerId), any()))
                .thenReturn(Optional.of(liveAttribution()));
        when(affiliateRepo.findById(affiliateId)).thenReturn(Optional.of(affiliate()));
        when(commissionRepo.findByAffiliateIdOrderByCreatedAtDesc(affiliateId)).thenReturn(List.of());

        service.onOrderPlaced(orderId, customerId, 3200, "EUR");

        ArgumentCaptor<AffiliateCommissionEntity> cap = ArgumentCaptor.forClass(AffiliateCommissionEntity.class);
        verify(commissionRepo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("REVIEW");
    }

    @Test
    void requestPayout_below_minimum_throws() {
        when(affiliateRepo.findByUser_Id(affiliateUserId)).thenReturn(Optional.of(affiliate()));
        when(payoutRepo.existsByAffiliateIdAndStatus(affiliateId, "REQUESTED")).thenReturn(false);
        AffiliateCommissionEntity c = AffiliateCommissionEntity.builder().affiliateId(affiliateId)
                .conversionId(UUID.randomUUID()).amountCents(1000).currency("EUR").percentage(java.math.BigDecimal.TEN)
                .status("APPROVED").build();
        when(commissionRepo.findByAffiliateIdAndStatus(affiliateId, "APPROVED")).thenReturn(List.of(c));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.requestPayout(affiliateUserId))
                .isInstanceOf(BusinessException.class);
        verify(payoutRepo, never()).save(any());
    }

    @Test
    void payoutApproved_below_minimum_does_not_pay() {
        AffiliateEntity a = affiliate();
        when(affiliateRepo.findById(affiliateId)).thenReturn(Optional.of(a));
        AffiliateCommissionEntity c1 = AffiliateCommissionEntity.builder().affiliateId(affiliateId)
                .conversionId(UUID.randomUUID()).amountCents(1000).currency("EUR").percentage(BigDecimal.TEN)
                .status("APPROVED").build();
        when(commissionRepo.findByAffiliateIdAndStatus(affiliateId, "APPROVED")).thenReturn(List.of(c1));

        long paid = service.payoutApproved(affiliateId, false); // 10.00 < 50.00 min

        assertThat(paid).isEqualTo(0);
        verify(walletUseCase, never()).adminTopup(any(), anyLong(), any(), any());
    }
}
