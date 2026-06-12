package com.nexaplatform.dropshipping.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Transport DTOs for the affiliate program (DROP-647). */
public final class AffiliateDtos {

    private AffiliateDtos() {
    }

    public record ReferralCodeView(UUID id, String code, String label, boolean active, int clicks, String url) {
    }

    public record CommissionView(UUID id, long amountCents, String currency, BigDecimal percentage, String status,
            Instant createdAt, Instant approvedAt, Instant paidAt, UUID orderId, long baseAmountCents) {
    }

    public record AffiliateStats(int clicks, int conversions, long pendingCents, long approvedCents, long paidCents,
            String currency) {
    }

    /** Customer-facing affiliate dashboard (DROP-649). */
    public record AffiliateDashboardView(UUID id, String status, BigDecimal commissionPercent, String shareBaseUrl,
            List<ReferralCodeView> codes, AffiliateStats stats, List<CommissionView> recentCommissions) {
    }

    /** Admin row (DROP-648). */
    public record AdminAffiliateRow(UUID id, UUID userId, String name, String email, String status, int codesCount,
            int referralsCount, long earningsCents, long paidCents, long pendingCents, long approvedCents,
            BigDecimal commissionPercentOverride, String currency) {
    }

    public record AdminAffiliateDetail(AdminAffiliateRow row, List<ReferralCodeView> codes,
            List<CommissionView> commissions) {
    }

    public record ProgramConfigView(BigDecimal defaultPercent, int attributionWindowDays, int returnPeriodDays,
            long minPayoutCents, String currency, String attributionModel) {
    }

    /* -------- request bodies -------- */

    public record TrackRequest(String ref, String visitorToken) {
    }

    public record TrackResponse(String visitorToken, boolean attributed) {
    }

    public record BindRequest(String visitorToken) {
    }

    public record AddCodeRequest(String label) {
    }

    public record StatusRequest(String status) {
    }

    public record ConfigUpdateRequest(BigDecimal defaultPercent, Integer attributionWindowDays, Integer returnPeriodDays,
            Long minPayoutCents, String currency) {
    }
}
