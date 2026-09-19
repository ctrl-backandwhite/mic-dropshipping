package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.dto.AffiliateDtos.*;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.AffiliateViewMapper;
import com.nexaplatform.dropshipping.application.service.AdminAffiliateQueryService;
import com.nexaplatform.dropshipping.application.service.AffiliateProgramService;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.integration.search.AffiliateIndexer;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.*;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateProgramConfigEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin affiliate management (DROP-648): list affiliates with metrics, drill into one, change
 * status, run commission approvals, pay out to wallets, and tune the program config.
 */
@RestController
@RequestMapping("/api/admin/affiliates")
@RequiredArgsConstructor
public class AdminAffiliateController {

    private final AffiliateProgramService service;
    private final AffiliateViewMapper mapper;
    private final AdminAffiliateQueryService affiliateQuery;
    private final AffiliateIndexer affiliateIndexer;
    private final CurrencyRateService currencyRateService;

    @GetMapping
    public ResponseEntity<PageResponse<AdminAffiliateRow>> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        AdminAffiliateQueryService.AffiliatePage result = affiliateQuery.page(q, status, page, size);
        return ResponseEntity.ok(new PageResponse<>(result.rows(), page, size, result.total(),
                (int) Math.ceil((double) result.total() / Math.max(1, size))));
    }

    /** Reindexa todos los afiliados en OpenSearch (botón "Reindexar" del admin). */
    @PostMapping("/reindex")
    public ResponseEntity<Map<String, Object>> reindex() {
        return ResponseEntity.ok(Map.of("indexed", affiliateIndexer.reindexAll()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AdminAffiliateDetail> detail(@PathVariable UUID id) {
        AffiliateProgramConfigEntity cfg = service.config();
        String currency = cfg.getCurrency();
        AffiliateEntity a = service.allAffiliates().stream().filter(x -> x.getId().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Affiliate not found"));
        List<AffiliateReferralCodeEntity> codes = service.listCodes(id);
        List<AffiliateConversionEntity> convs = service.conversionsForAffiliate(id);
        List<AffiliateCommissionEntity> comms = service.commissionsForAffiliate(id);
        Map<UUID, AffiliateConversionEntity> convById = mapper.indexByConversionId(convs);
        AdminAffiliateRow row = mapper.toAdminRow(a, codes, comms, currency);
        return ResponseEntity.ok(new AdminAffiliateDetail(row, codes.stream().map(mapper::toCodeView).toList(),
                comms.stream().map(c -> mapper.toCommissionView(c, convById, cfg.getReturnPeriodDays())).toList()));
    }

    @PostMapping("/{id}/status")
    public ResponseEntity<Void> setStatus(@PathVariable UUID id, @RequestBody StatusRequest req) {
        service.setAffiliateStatus(id, req.status());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/payout")
    public ResponseEntity<Map<String, Long>> payout(@PathVariable UUID id) {
        long paid = service.payoutApproved(id, true);
        return ResponseEntity.ok(Map.of("paidCents", paid));
    }

    @PostMapping("/approve-due")
    public ResponseEntity<Map<String, Integer>> approveDue() {
        return ResponseEntity.ok(Map.of("approved", service.approveDueCommissions()));
    }

    /* ---- DROP-651: payout requests (operator approval required) ---- */

    /**
     * Nombre con el que se identifica al afiliado en la bandeja de pagos. El orden de comprobación es
     * el importante: manda el nombre que el propio usuario eligió y, solo si no tiene ninguno, se cae
     * a su email; un pago sin usuario asociado se queda sin nombre en lugar de reventar.
     */
    private static String displayNameOf(UserEntity user) {
        if (user == null) {
            return null;
        }
        return user.getDisplayName() != null && !user.getDisplayName().isBlank()
                ? user.getDisplayName()
                : user.getEmail();
    }

    @GetMapping("/payouts/pending")
    public ResponseEntity<List<PendingPayoutView>> pendingPayouts() {
        Map<UUID, String> nameByAffiliateId = new HashMap<>();
        service.allAffiliates().forEach(a -> nameByAffiliateId.put(a.getId(), displayNameOf(a.getUser())));
        List<PendingPayoutView> views = service.pendingPayouts().stream()
                .map(payout -> new PendingPayoutView(payout.getId(), payout.getAffiliateId(),
                        nameByAffiliateId.get(payout.getAffiliateId()), payout.getAmountCents(),
                        currencyRateService.formatDisplay(BigDecimal.valueOf(payout.getAmountCents()).movePointLeft(2),
                                payout.getCurrency()),
                        payout.getCurrency(), payout.getMethod(), payout.getDestHolder(), payout.getDestIban(),
                        payout.getDestBic(), payout.getDestPaypalEmail(), payout.getCommissionCount(),
                        payout.getRequestedAt() != null ? payout.getRequestedAt().toString() : null))
                .toList();
        return ResponseEntity.ok(views);
    }

    @PostMapping("/payouts/{payoutId}/approve")
    public ResponseEntity<Map<String, Object>> approvePayout(Authentication auth, @PathVariable UUID payoutId,
            @RequestBody(required = false) ApprovePayoutRequest req) {
        UUID adminId = UUID.fromString(auth.getName());
        String reference = req != null ? req.reference() : null;
        AffiliatePayoutEntity p = service.approvePayout(payoutId, adminId, reference);
        return ResponseEntity.ok(Map.of("status", p.getStatus(), "reference",
                p.getPaidReference() != null ? p.getPaidReference() : ""));
    }

    @PostMapping("/payouts/{payoutId}/reject")
    public ResponseEntity<Void> rejectPayout(@PathVariable UUID payoutId, @RequestBody(required = false) Map<String, String> body) {
        service.rejectPayout(payoutId, body != null ? body.get("reason") : null);
        return ResponseEntity.noContent().build();
    }

    /* ---- DROP-652: resolve a commission flagged for fraud review ---- */

    @PostMapping("/commissions/{commissionId}/review")
    public ResponseEntity<Void> resolveReview(@PathVariable UUID commissionId, @RequestParam boolean approve) {
        service.resolveReview(commissionId, approve);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/config")
    public ResponseEntity<ProgramConfigView> getConfig() {
        return ResponseEntity.ok(mapper.toConfigView(service.config()));
    }

    @PutMapping("/config")
    public ResponseEntity<ProgramConfigView> updateConfig(@RequestBody ConfigUpdateRequest req) {
        AffiliateProgramConfigEntity c = service.updateConfig(req.defaultPercent(), req.attributionWindowDays(), req.returnPeriodDays(),
                req.minPayoutCents(), req.currency(), req.maxCommissionPeriodCents());
        return ResponseEntity.ok(mapper.toConfigView(c));
    }
}
