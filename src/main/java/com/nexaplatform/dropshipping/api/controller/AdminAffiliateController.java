package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.dto.AffiliateDtos.*;
import com.nexaplatform.dropshipping.api.mapper.AffiliateViewMapper;
import com.nexaplatform.dropshipping.application.service.AffiliateProgramService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    @GetMapping
    public ResponseEntity<List<AdminAffiliateRow>> list() {
        String currency = service.config().getCurrency();
        List<AdminAffiliateRow> rows = service.allAffiliates().stream().map(a -> {
            List<AffiliateCommissionEntity> comms = service.commissionsForAffiliate(a.getId());
            return mapper.toAdminRow(a, service.listCodes(a.getId()), comms, currency);
        }).toList();
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/{id}")
    public ResponseEntity<AdminAffiliateDetail> detail(@PathVariable UUID id) {
        String currency = service.config().getCurrency();
        AffiliateEntity a = service.allAffiliates().stream().filter(x -> x.getId().equals(id)).findFirst()
                .orElseThrow(() -> new com.nexaplatform.dropshipping.api.exception.NotFoundException("Affiliate not found"));
        List<AffiliateReferralCodeEntity> codes = service.listCodes(id);
        List<AffiliateConversionEntity> convs = service.conversionsForAffiliate(id);
        List<AffiliateCommissionEntity> comms = service.commissionsForAffiliate(id);
        Map<UUID, AffiliateConversionEntity> convById = mapper.indexByConversionId(convs);
        var row = mapper.toAdminRow(a, codes, comms, currency);
        return ResponseEntity.ok(new AdminAffiliateDetail(row, codes.stream().map(mapper::toCodeView).toList(),
                comms.stream().map(c -> mapper.toCommissionView(c, convById)).toList()));
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

    @GetMapping("/payouts/pending")
    public ResponseEntity<List<com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliatePayoutEntity>> pendingPayouts() {
        return ResponseEntity.ok(service.pendingPayouts());
    }

    @PostMapping("/payouts/{payoutId}/approve")
    public ResponseEntity<Map<String, Object>> approvePayout(@PathVariable UUID payoutId) {
        var p = service.approvePayout(payoutId);
        return ResponseEntity.ok(Map.of("status", p.getStatus(), "amountCents", p.getAmountCents()));
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
        var c = service.updateConfig(req.defaultPercent(), req.attributionWindowDays(), req.returnPeriodDays(),
                req.minPayoutCents(), req.currency(), req.maxCommissionPeriodCents());
        return ResponseEntity.ok(mapper.toConfigView(c));
    }
}
