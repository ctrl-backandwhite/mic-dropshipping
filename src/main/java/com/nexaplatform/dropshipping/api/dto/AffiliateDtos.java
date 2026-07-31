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

    // approvesAt = fecha ESTIMADA en que una comisión PENDIENTE pasará a APROBADA (creación +
    // periodo de devolución). Permite al front pintar la cuenta atrás en la wallet. Null si la
    // comisión ya no está PENDIENTE.
    public record CommissionView(UUID id, long amountCents, String currency, BigDecimal percentage, String status,
            Instant createdAt, Instant approvedAt, Instant paidAt, UUID orderId, long baseAmountCents,
            Instant approvesAt) {
    }

    public record AffiliateStats(int clicks, int conversions, long pendingCents, long approvedCents, long paidCents,
            String currency) {
    }

    /** Customer-facing affiliate dashboard (DROP-649/650/651). */
    public record AffiliateDashboardView(UUID id, String status, BigDecimal commissionPercent, boolean joined,
            boolean canRequestPayout, long minPayoutCents, boolean payoutRequested, List<ReferralCodeView> codes,
            AffiliateStats stats, List<CommissionView> recentCommissions) {
    }

    /** Admin row (DROP-648). */
    public record AdminAffiliateRow(UUID id, UUID userId, String name, String email, String status, int codesCount,
            int clicks, int referralsCount, long earningsCents, long paidCents, long pendingCents, long approvedCents,
            BigDecimal commissionPercentOverride, String currency) {
    }

    public record AdminAffiliateDetail(AdminAffiliateRow row, List<ReferralCodeView> codes,
            List<CommissionView> commissions) {
    }

    public record ProgramConfigView(BigDecimal defaultPercent, int attributionWindowDays, int returnPeriodDays,
            long minPayoutCents, String currency, String attributionModel, long maxCommissionPeriodCents,
            int maxPeriodDays, int clickDedupMinutes) {
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
            Long minPayoutCents, String currency, Long maxCommissionPeriodCents) {
    }

    /** Datos de cobro del afiliado (IBAN enmascarado en lectura). */
    public record PayoutProfileView(String payoutMethod, String bankHolder, String bankIbanMasked,
            String bankBic, String paypalEmail, boolean hasBank, boolean hasPaypal) {
    }

    /** Alta/edición de datos de cobro; requiere la contraseña del usuario. */
    public record PayoutProfileUpdateRequest(String bankHolder, String iban, String bic, String paypalEmail,
            String preferredMethod, String password) {
    }

    /** Solicitud de pago: método elegido (WALLET | BANK | PAYPAL). */
    public record PayoutRequest(String method) {
    }

    /** Fila de payout pendiente para el ADMIN, con el destino a la vista para ejecutarlo. */
    public record PendingPayoutView(UUID id, UUID affiliateId, String affiliateName, long amountCents,
            String amountFormatted, String currency, String method, String destHolder, String destIban,
            String destBic, String destPaypalEmail, int commissionCount, String requestedAt) {
    }

    /** Aprobación de payout externo: referencia de la transferencia/PayPal. */
    public record ApprovePayoutRequest(String reference) {
    }
}
