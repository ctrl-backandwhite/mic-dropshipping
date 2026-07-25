package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.AffiliateDtos.*;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Maps affiliate entities to the transport views (DROP-647/648/649). */
@Component
public class AffiliateViewMapper {

    public ReferralCodeView toCodeView(AffiliateReferralCodeEntity c) {
        return new ReferralCodeView(c.getId(), c.getCode(), c.getLabel(), c.isActive(), c.getClicks(),
                "/?ref=" + c.getCode());
    }

    public CommissionView toCommissionView(AffiliateCommissionEntity comm, Map<UUID, AffiliateConversionEntity> convById,
            int returnPeriodDays) {
        AffiliateConversionEntity conv = convById.get(comm.getConversionId());
        // Solo las PENDIENTES tienen fecha de aprobación futura: creación + periodo de devolución.
        java.time.Instant approvesAt = "PENDING".equals(comm.getStatus()) && comm.getCreatedAt() != null
                ? comm.getCreatedAt().plus(java.time.Duration.ofDays(Math.max(0, returnPeriodDays)))
                : null;
        return new CommissionView(comm.getId(), comm.getAmountCents(), comm.getCurrency(), comm.getPercentage(),
                comm.getStatus(), comm.getCreatedAt(), comm.getApprovedAt(), comm.getPaidAt(),
                conv != null ? conv.getOrderId() : null, conv != null ? conv.getBaseAmountCents() : 0L, approvesAt);
    }

    public AffiliateStats stats(List<AffiliateReferralCodeEntity> codes, List<AffiliateConversionEntity> conversions,
            List<AffiliateCommissionEntity> commissions, String currency) {
        // DROP-694: every conversion requires an attributed click, so clicks can never be fewer than
        // conversions. Legacy/seed data left the per-code click counter at 0 while commissions existed,
        // showing "0 clicks but paid commissions". Enforce the clicks >= conversions invariant on read.
        int clicks = Math.max(codes.stream().mapToInt(AffiliateReferralCodeEntity::getClicks).sum(), conversions.size());
        long pending = sumByStatus(commissions, "PENDING");
        long approved = sumByStatus(commissions, "APPROVED");
        long paid = sumByStatus(commissions, "PAID");
        return new AffiliateStats(clicks, conversions.size(), pending, approved, paid, currency);
    }

    private long sumByStatus(List<AffiliateCommissionEntity> commissions, String status) {
        return commissions.stream().filter(c -> status.equals(c.getStatus()))
                .mapToLong(AffiliateCommissionEntity::getAmountCents).sum();
    }

    public Map<UUID, AffiliateConversionEntity> indexByConversionId(List<AffiliateConversionEntity> conversions) {
        return conversions.stream().collect(Collectors.toMap(AffiliateConversionEntity::getId, c -> c, (a, b) -> a));
    }

    public AdminAffiliateRow toAdminRow(AffiliateEntity a, List<AffiliateReferralCodeEntity> codes,
            List<AffiliateCommissionEntity> commissions, String currency) {
        // DROP-694: clicks can never be fewer than confirmed conversions (referralsCount). Guards the
        // admin panel against the "0 clicks but paid commissions" inconsistency from legacy/seed data.
        int clicks = Math.max(codes.stream().mapToInt(AffiliateReferralCodeEntity::getClicks).sum(),
                a.getReferralsCount());
        return new AdminAffiliateRow(a.getId(), a.getUser() != null ? a.getUser().getId() : null,
                a.getUser() != null ? a.getUser().getDisplayName() : null,
                a.getUser() != null ? a.getUser().getEmail() : null, a.getStatus(), codes.size(), clicks,
                a.getReferralsCount(), a.getEarningsUsdCents(), a.getPayoutUsdCents(),
                sumByStatus(commissions, "PENDING"), sumByStatus(commissions, "APPROVED"),
                a.getCommissionPercentOverride(), currency);
    }

    public ProgramConfigView toConfigView(AffiliateProgramConfigEntity c) {
        return new ProgramConfigView(c.getDefaultPercent(), c.getAttributionWindowDays(), c.getReturnPeriodDays(),
                c.getMinPayoutCents(), c.getCurrency(), c.getAttributionModel(), c.getMaxCommissionPeriodCents(),
                c.getMaxPeriodDays(), c.getClickDedupMinutes());
    }
}
