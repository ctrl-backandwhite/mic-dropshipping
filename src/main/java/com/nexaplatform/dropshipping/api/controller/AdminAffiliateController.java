package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.dto.AffiliateDtos.*;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.mapper.AffiliateViewMapper;
import com.nexaplatform.dropshipping.application.service.AffiliateProgramService;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.infrastructure.integration.search.AffiliateIndexer;
import com.nexaplatform.dropshipping.infrastructure.integration.search.AffiliateSearchService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private final AffiliateSearchService affiliateSearch;
    private final AffiliateIndexer affiliateIndexer;

    @GetMapping
    public ResponseEntity<PageResponse<AdminAffiliateRow>> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String currency = service.config().getCurrency();
        // Primario: OpenSearch (índice `affiliates`) → página de IDs (reciente→antigua) filtrada; solo se
        // construye la fila (códigos/comisiones) de esa página cargándola de la BD.
        Optional<AffiliateSearchService.IdPage> idx = affiliateSearch.pageIds(q, status, page, size);
        if (idx.isPresent()) {
            Map<UUID, AffiliateEntity> byId = new HashMap<>();
            service.allAffiliates().forEach(a -> byId.put(a.getId(), a));
            List<AdminAffiliateRow> rows = idx.get().ids().stream().map(byId::get).filter(Objects::nonNull)
                    .map(a -> mapper.toAdminRow(a, service.listCodes(a.getId()), service.commissionsForAffiliate(a.getId()),
                            currency))
                    .toList();
            long total = idx.get().total();
            return ResponseEntity.ok(new PageResponse<>(rows, page, size, total,
                    (int) Math.ceil((double) total / Math.max(1, size))));
        }
        // Fallback (OpenSearch caído): construye todas las filas + página en memoria (dataset acotado).
        List<AdminAffiliateRow> all = service.allAffiliates().stream()
                .map(a -> mapper.toAdminRow(a, service.listCodes(a.getId()), service.commissionsForAffiliate(a.getId()),
                        currency))
                .toList();
        int from = Math.min(Math.max(0, page) * size, all.size());
        int to = Math.min(from + size, all.size());
        return ResponseEntity.ok(new PageResponse<>(all.subList(from, to), page, size, all.size(),
                (int) Math.ceil((double) all.size() / Math.max(1, size))));
    }

    /** Reindexa todos los afiliados en OpenSearch (botón "Reindexar" del admin). */
    @PostMapping("/reindex")
    public ResponseEntity<Map<String, Object>> reindex() {
        return ResponseEntity.ok(Map.of("indexed", affiliateIndexer.reindexAll()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AdminAffiliateDetail> detail(@PathVariable UUID id) {
        String currency = service.config().getCurrency();
        AffiliateEntity a = service.allAffiliates().stream().filter(x -> x.getId().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Affiliate not found"));
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
    public ResponseEntity<List<AffiliatePayoutEntity>> pendingPayouts() {
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
