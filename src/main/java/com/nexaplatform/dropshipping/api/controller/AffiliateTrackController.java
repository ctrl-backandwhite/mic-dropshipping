package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.dto.AffiliateDtos.TrackRequest;
import com.nexaplatform.dropshipping.api.dto.AffiliateDtos.TrackResponse;
import com.nexaplatform.dropshipping.application.service.AffiliateProgramService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Public referral-click tracking (DROP-645). Anonymous: the SPA calls this when it sees a
 * {@code ?ref=CODE}, sending (or receiving) a visitor token it persists as a cookie/localStorage.
 */
@RestController
@RequestMapping("/api/storefront/affiliate")
@RequiredArgsConstructor
public class AffiliateTrackController {

    private final AffiliateProgramService affiliateProgramService;

    @PostMapping("/track")
    public ResponseEntity<TrackResponse> track(@RequestBody TrackRequest req) {
        String token = req.visitorToken() != null && !req.visitorToken().isBlank() ? req.visitorToken()
                : UUID.randomUUID().toString();
        boolean attributed = affiliateProgramService.recordClick(req.ref(), token).isPresent();
        return ResponseEntity.ok(new TrackResponse(token, attributed));
    }
}
