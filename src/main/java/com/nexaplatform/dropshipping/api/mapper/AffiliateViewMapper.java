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

    public CommissionView toCommissionView(AffiliateCommissionEntity comm, Map<UUID, AffiliateConversionEntity> convById) {
        AffiliateConversionEntity conv = convById.get(comm.getConversionId());
        return new CommissionView(comm.getId(), comm.getAmountCents(), comm.getCurrency(), comm.getPercentage(),
                comm.getStatus(), comm.getCreatedAt(), comm.getApprovedAt(), comm.getPaidAt(),
                conv != null ? conv.getOrderId() : null, conv != null ? conv.getBaseAmountCents() : 0L);
    }

    public AffiliateStats stats(List<AffiliateReferralCodeEntity> codes, List<AffiliateConversionEntity> conversions,
            List<AffiliateCommissionEntity> commissions, String currency) {
        int clicks = codes.stream().mapToInt(AffiliateReferralCodeEntity::getClicks).sum();
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

    public AdminAffiliateRow toAdminRow(AffiliateEntity a, int codesCount, List<AffiliateCommissionEntity> commissions,
            String currency) {
        return new AdminAffiliateRow(a.getId(), a.getUser() != null ? a.getUser().getId() : null,
                a.getUser() != null ? a.getUser().getDisplayName() : null,
                a.getUser() != null ? a.getUser().getEmail() : null, a.getStatus(), codesCount, a.getReferralsCount(),
                a.getEarningsUsdCents(), a.getPayoutUsdCents(), sumByStatus(commissions, "PENDING"),
                sumByStatus(commissions, "APPROVED"), a.getCommissionPercentOverride(), currency);
    }

    public ProgramConfigView toConfigView(AffiliateProgramConfigEntity c) {
        return new ProgramConfigView(c.getDefaultPercent(), c.getAttributionWindowDays(), c.getReturnPeriodDays(),
                c.getMinPayoutCents(), c.getCurrency(), c.getAttributionModel(), c.getMaxCommissionPeriodCents(),
                c.getMaxPeriodDays(), c.getClickDedupMinutes());
    }
}
