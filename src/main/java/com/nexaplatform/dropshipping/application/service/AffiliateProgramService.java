package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.*;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Affiliate program engine (DROP-644/645/646): referral codes, click attribution (last-click,
 * cookie-based, within the attribution window), conversion capture on a confirmed order, the
 * commission state machine (PENDING → APPROVED after the return period → PAID via wallet credit,
 * or REJECTED on cancel/refund) and payouts to the affiliate's wallet.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AffiliateProgramService {

    private final AffiliateJpaRepositoryAdapter affiliateRepo;
    private final AffiliateReferralCodeRepository codeRepo;
    private final AffiliateAttributionRepository attrRepo;
    private final AffiliateConversionRepository conversionRepo;
    private final AffiliateCommissionRepository commissionRepo;
    private final AffiliateProgramConfigRepository configRepo;
    private final UserRepository userRepository;
    private final WalletUseCase walletUseCase;

    /* ============================ Config ============================ */

    @Transactional(readOnly = true)
    public AffiliateProgramConfigEntity config() {
        return configRepo.findFirstByOrderByCreatedAtAsc().orElseGet(() -> AffiliateProgramConfigEntity.builder()
                .defaultPercent(new BigDecimal("10.000")).attributionWindowDays(30).returnPeriodDays(14)
                .minPayoutCents(5000).currency("EUR").attributionModel("LAST_CLICK").build());
    }

    @Transactional
    public AffiliateProgramConfigEntity updateConfig(BigDecimal defaultPercent, Integer windowDays, Integer returnDays,
            Long minPayoutCents, String currency) {
        AffiliateProgramConfigEntity c = configRepo.findFirstByOrderByCreatedAtAsc()
                .orElseGet(() -> configRepo.save(config()));
        if (defaultPercent != null) c.setDefaultPercent(defaultPercent);
        if (windowDays != null) c.setAttributionWindowDays(windowDays);
        if (returnDays != null) c.setReturnPeriodDays(returnDays);
        if (minPayoutCents != null) c.setMinPayoutCents(minPayoutCents);
        if (currency != null && !currency.isBlank()) c.setCurrency(currency);
        return configRepo.save(c);
    }

    /* ============================ Affiliate + codes (DROP-644) ============================ */

    /** Returns the user's affiliate account, creating an ACTIVE one (with a first code) on demand. */
    @Transactional
    public AffiliateEntity getOrCreateForUser(UUID userId) {
        UserEntity user = userRepository.findById(userId).orElseThrow(
                () -> new com.nexaplatform.dropshipping.api.exception.NotFoundException("User not found"));
        // Data rule: admin/operator accounts cannot be affiliates.
        String role = user.getRole() != null ? user.getRole().name() : "";
        if ("ADMIN".equals(role) || "OPERATOR".equals(role)) {
            throw new com.nexaplatform.dropshipping.api.exception.BusinessException(
                    "Las cuentas de administración no pueden ser afiliados");
        }
        AffiliateEntity affiliate = affiliateRepo.findByUser_Id(userId).orElseGet(() -> {
            AffiliateEntity a = AffiliateEntity.builder().user(user).code(generateUniqueCode(user)).active(true)
                    .status("ACTIVE").build();
            return affiliateRepo.save(a);
        });
        // Ensure at least one referral code row exists.
        if (codeRepo.findByAffiliateIdOrderByCreatedAtAsc(affiliate.getId()).isEmpty()) {
            codeRepo.save(AffiliateReferralCodeEntity.builder().affiliate(affiliate)
                    .code(affiliate.getCode() != null ? affiliate.getCode() : generateUniqueCode(user))
                    .label("Primary").active(true).build());
        }
        return affiliate;
    }

    @Transactional(readOnly = true)
    public List<AffiliateReferralCodeEntity> listCodes(UUID affiliateId) {
        return codeRepo.findByAffiliateIdOrderByCreatedAtAsc(affiliateId);
    }

    @Transactional
    public AffiliateReferralCodeEntity addCode(UUID affiliateId, String label) {
        AffiliateEntity affiliate = affiliateRepo.findById(affiliateId).orElseThrow(
                () -> new com.nexaplatform.dropshipping.api.exception.NotFoundException("Affiliate not found"));
        return codeRepo.save(AffiliateReferralCodeEntity.builder().affiliate(affiliate)
                .code(generateUniqueCode(affiliate.getUser())).label(label != null ? label : "Link").active(true)
                .build());
    }

    @Transactional
    public AffiliateReferralCodeEntity setCodeActive(UUID codeId, boolean active) {
        AffiliateReferralCodeEntity c = codeRepo.findById(codeId).orElseThrow(
                () -> new com.nexaplatform.dropshipping.api.exception.NotFoundException("Code not found"));
        c.setActive(active);
        return codeRepo.save(c);
    }

    private String generateUniqueCode(UserEntity u) {
        String base = (u == null ? "ref" : (u.getDisplayName() == null ? u.getEmail() : u.getDisplayName()))
                .toLowerCase().replaceAll("[^a-z0-9]", "");
        if (base.isBlank()) base = "ref";
        if (base.length() > 8) base = base.substring(0, 8);
        for (int i = 0; i < 12; i++) {
            String candidate = base + "-" + UUID.randomUUID().toString().substring(0, 6);
            if (!codeRepo.existsByCodeIgnoreCase(candidate)) {
                return candidate;
            }
        }
        return base + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    /* ============================ Attribution (DROP-645) ============================ */

    /** Records a referral click and returns the visitor token to persist as a cookie. */
    @Transactional
    public Optional<AffiliateAttributionEntity> recordClick(String code, String visitorToken) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        Optional<AffiliateReferralCodeEntity> codeOpt = codeRepo.findByCodeIgnoreCase(code.trim());
        if (codeOpt.isEmpty() || !codeOpt.get().isActive()) {
            return Optional.empty(); // unknown/inactive code → no attribution
        }
        AffiliateReferralCodeEntity rc = codeOpt.get();
        if (!"ACTIVE".equals(rc.getAffiliate().getStatus())) {
            return Optional.empty();
        }
        rc.setClicks(rc.getClicks() + 1);
        codeRepo.save(rc);
        Instant now = Instant.now();
        int windowDays = config().getAttributionWindowDays();
        AffiliateAttributionEntity attr = AffiliateAttributionEntity.builder().referralCodeId(rc.getId())
                .affiliateId(rc.getAffiliate().getId()).visitorToken(visitorToken).clickedAt(now)
                .expiresAt(now.plus(Duration.ofDays(windowDays))).build();
        return Optional.of(attrRepo.save(attr));
    }

    /** Binds an anonymous (cookie) attribution to a customer once they log in / register. */
    @Transactional
    public void bindVisitorToUser(String visitorToken, UUID userId) {
        if (visitorToken == null || visitorToken.isBlank() || userId == null) {
            return;
        }
        attrRepo.findTopByVisitorTokenAndExpiresAtAfterOrderByClickedAtDesc(visitorToken, Instant.now())
                .ifPresent(attr -> {
                    // Do not let an affiliate self-refer.
                    if (!isSelf(attr.getAffiliateId(), userId)) {
                        attr.setReferredUserId(userId);
                        attrRepo.save(attr);
                    }
                });
    }

    private boolean isSelf(UUID affiliateId, UUID userId) {
        return affiliateRepo.findById(affiliateId)
                .map(a -> a.getUser() != null && userId.equals(a.getUser().getId())).orElse(false);
    }

    /* ============================ Conversion + commission (DROP-646) ============================ */

    /**
     * Captures a conversion + PENDING commission when a customer with a live attribution places a
     * valid order. Idempotent per order; ignores self-referrals and orders without attribution.
     */
    @Transactional
    public void onOrderPlaced(UUID orderId, UUID userId, long subtotalCents, String currency) {
        if (orderId == null || userId == null || subtotalCents <= 0) {
            return;
        }
        if (conversionRepo.existsByOrderId(orderId)) {
            return; // idempotent
        }
        Optional<AffiliateAttributionEntity> attrOpt = attrRepo
                .findTopByReferredUserIdAndExpiresAtAfterOrderByClickedAtDesc(userId, Instant.now());
        if (attrOpt.isEmpty()) {
            return; // no live attribution
        }
        AffiliateAttributionEntity attr = attrOpt.get();
        if (isSelf(attr.getAffiliateId(), userId)) {
            return;
        }
        AffiliateEntity affiliate = affiliateRepo.findById(attr.getAffiliateId()).orElse(null);
        if (affiliate == null || !"ACTIVE".equals(affiliate.getStatus())) {
            return;
        }
        String ccy = currency != null ? currency : "USD";
        AffiliateConversionEntity conv = conversionRepo.save(AffiliateConversionEntity.builder()
                .affiliateId(affiliate.getId()).referralCodeId(attr.getReferralCodeId()).referredUserId(userId)
                .orderId(orderId).baseAmountCents(subtotalCents).currency(ccy).status("CONFIRMED").build());

        BigDecimal pct = affiliate.getCommissionPercentOverride() != null
                ? affiliate.getCommissionPercentOverride()
                : config().getDefaultPercent();
        long amount = BigDecimal.valueOf(subtotalCents).multiply(pct).divide(BigDecimal.valueOf(100), 0,
                RoundingMode.HALF_UP).longValue();
        commissionRepo.save(AffiliateCommissionEntity.builder().affiliateId(affiliate.getId())
                .conversionId(conv.getId()).amountCents(amount).currency(ccy).percentage(pct).status("PENDING")
                .note("Auto: " + pct + "% de " + subtotalCents + " " + ccy).build());

        affiliate.setReferralsCount(affiliate.getReferralsCount() + 1);
        affiliate.setEarningsUsdCents(affiliate.getEarningsUsdCents() + amount);
        affiliateRepo.save(affiliate);
        log.info("::> [AFFILIATE] Conversion {} → commission {} {} for affiliate {}", conv.getId(), amount, ccy,
                affiliate.getId());
    }

    /** Cancels the conversion and rejects its commission (unless already paid) on order cancel/refund. */
    @Transactional
    public void rejectForOrder(UUID orderId) {
        conversionRepo.findByOrderId(orderId).ifPresent(conv -> {
            conv.setStatus("CANCELLED");
            conversionRepo.save(conv);
            commissionRepo.findByConversionId(conv.getId()).ifPresent(comm -> {
                if (!"PAID".equals(comm.getStatus())) {
                    comm.setStatus("REJECTED");
                    comm.setNote("Pedido cancelado/reembolsado");
                    commissionRepo.save(comm);
                    affiliateRepo.findById(comm.getAffiliateId()).ifPresent(a -> {
                        a.setEarningsUsdCents(Math.max(0, a.getEarningsUsdCents() - comm.getAmountCents()));
                        affiliateRepo.save(a);
                    });
                }
            });
        });
    }

    /** Moves PENDING commissions past the return period to APPROVED. Returns how many were approved. */
    @Transactional
    public int approveDueCommissions() {
        int days = config().getReturnPeriodDays();
        Instant cutoff = Instant.now().minus(Duration.ofDays(days));
        int approved = 0;
        for (AffiliateCommissionEntity comm : commissionRepo.findByStatus("PENDING")) {
            if (comm.getCreatedAt() != null && comm.getCreatedAt().isBefore(cutoff)) {
                comm.setStatus("APPROVED");
                comm.setApprovedAt(Instant.now());
                commissionRepo.save(comm);
                approved++;
            }
        }
        return approved;
    }

    /* ============================ Payout to wallet ============================ */

    /**
     * Settles APPROVED commissions for an affiliate by crediting their wallet, provided the total
     * meets the minimum payout. Returns the credited amount in cents (0 if below threshold).
     */
    @Transactional
    public long payoutApproved(UUID affiliateId, boolean force) {
        AffiliateEntity affiliate = affiliateRepo.findById(affiliateId).orElseThrow(
                () -> new com.nexaplatform.dropshipping.api.exception.NotFoundException("Affiliate not found"));
        List<AffiliateCommissionEntity> approved = commissionRepo.findByAffiliateIdAndStatus(affiliateId, "APPROVED");
        long total = approved.stream().mapToLong(AffiliateCommissionEntity::getAmountCents).sum();
        if (total <= 0) {
            return 0;
        }
        if (!force && total < config().getMinPayoutCents()) {
            return 0; // below minimum payout threshold
        }
        UUID userId = affiliate.getUser().getId();
        var tx = walletUseCase.adminTopup(userId, total, "Affiliate commission payout",
                "affiliate-payout-" + affiliateId + "-" + Instant.now().toEpochMilli());
        UUID txId = tx != null ? tx.getId() : null;
        Instant now = Instant.now();
        for (AffiliateCommissionEntity comm : approved) {
            comm.setStatus("PAID");
            comm.setPaidAt(now);
            comm.setWalletTxId(txId);
            commissionRepo.save(comm);
        }
        affiliate.setPayoutUsdCents(affiliate.getPayoutUsdCents() + total);
        affiliateRepo.save(affiliate);
        log.info("::> [AFFILIATE] Paid out {} cents to affiliate {} (wallet tx {})", total, affiliateId, txId);
        return total;
    }

    /* ============================ Queries ============================ */

    @Transactional(readOnly = true)
    public List<AffiliateCommissionEntity> commissionsForAffiliate(UUID affiliateId) {
        return commissionRepo.findByAffiliateIdOrderByCreatedAtDesc(affiliateId);
    }

    @Transactional(readOnly = true)
    public List<AffiliateConversionEntity> conversionsForAffiliate(UUID affiliateId) {
        return conversionRepo.findByAffiliateIdOrderByCreatedAtDesc(affiliateId);
    }

    @Transactional(readOnly = true)
    public List<AffiliateEntity> allAffiliates() {
        return affiliateRepo.findAllWithUser();
    }

    /** Admin lifecycle change: PENDING / ACTIVE / SUSPENDED. */
    @Transactional
    public AffiliateEntity setAffiliateStatus(UUID affiliateId, String status) {
        String s = status == null ? "" : status.trim().toUpperCase();
        if (!s.equals("PENDING") && !s.equals("ACTIVE") && !s.equals("SUSPENDED")) {
            throw new com.nexaplatform.dropshipping.api.exception.BusinessException("Estado de afiliado no válido");
        }
        AffiliateEntity a = affiliateRepo.findById(affiliateId).orElseThrow(
                () -> new com.nexaplatform.dropshipping.api.exception.NotFoundException("Affiliate not found"));
        a.setStatus(s);
        a.setActive("ACTIVE".equals(s));
        return affiliateRepo.save(a);
    }
}
