package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.dto.AffiliateDtos.*;
import com.nexaplatform.dropshipping.api.mapper.AffiliateViewMapper;
import com.nexaplatform.dropshipping.application.service.AffiliateProgramService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Customer affiliate area (DROP-649): join the program, manage referral codes/links and see
 * clicks, conversions and commissions.
 */
@RestController
@RequestMapping("/api/me/affiliate")
@RequiredArgsConstructor
public class MeAffiliateController {

    private final AffiliateProgramService service;
    private final AffiliateViewMapper mapper;

    @GetMapping
    public ResponseEntity<AffiliateDashboardView> dashboard(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(buildDashboard(userId));
    }

    @PostMapping("/join")
    public ResponseEntity<AffiliateDashboardView> join(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        service.getOrCreateForUser(userId); // creates the affiliate + first code
        return ResponseEntity.ok(buildDashboard(userId));
    }

    @PostMapping("/codes")
    public ResponseEntity<ReferralCodeView> addCode(Authentication auth, @RequestBody AddCodeRequest req) {
        UUID userId = UUID.fromString(auth.getName());
        AffiliateEntity a = service.getOrCreateForUser(userId);
        return ResponseEntity.ok(mapper.toCodeView(service.addCode(a.getId(), req.label())));
    }

    @PostMapping("/codes/{codeId}/toggle")
    public ResponseEntity<ReferralCodeView> toggle(@PathVariable UUID codeId, @RequestParam boolean active) {
        return ResponseEntity.ok(mapper.toCodeView(service.setCodeActive(codeId, active)));
    }

    /** Binds an anonymous referral cookie to this authenticated customer (DROP-645). */
    @PostMapping("/bind")
    public ResponseEntity<Void> bind(Authentication auth, @RequestBody BindRequest req) {
        UUID userId = UUID.fromString(auth.getName());
        service.bindVisitorToUser(req.visitorToken(), userId);
        return ResponseEntity.noContent().build();
    }

    private AffiliateDashboardView buildDashboard(UUID userId) {
        AffiliateEntity a = service.getOrCreateForUser(userId);
        List<AffiliateReferralCodeEntity> codes = service.listCodes(a.getId());
        List<AffiliateConversionEntity> convs = service.conversionsForAffiliate(a.getId());
        List<AffiliateCommissionEntity> comms = service.commissionsForAffiliate(a.getId());
        var convById = mapper.indexByConversionId(convs);
        var config = service.config();
        var pct = a.getCommissionPercentOverride() != null ? a.getCommissionPercentOverride()
                : config.getDefaultPercent();
        return new AffiliateDashboardView(a.getId(), a.getStatus(), pct, "",
                codes.stream().map(mapper::toCodeView).toList(),
                mapper.stats(codes, convs, comms, config.getCurrency()),
                comms.stream().limit(20).map(c -> mapper.toCommissionView(c, convById)).toList());
    }
}
